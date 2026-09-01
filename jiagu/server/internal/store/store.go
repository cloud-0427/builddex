package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

var companyIDPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]{1,63}$`)

var (
	ErrNotFound          = errors.New("not found")
	ErrConflict          = errors.New("conflict")
	ErrUnauthorized      = errors.New("unauthorized")
	ErrLimitExceeded     = errors.New("limit exceeded")
	ErrAuthorization     = errors.New("company authorization is inactive")
	ErrChallengeUsed     = errors.New("challenge is invalid, expired, or already used")
	ErrAlreadyRevoked    = errors.New("release is already revoked")
	ErrInvalidTransition = errors.New("invalid release status transition")
)

type Manager struct {
	dir     string
	options ManagerOptions
	mu      sync.Mutex
	dbs     map[string]*databaseEntry
	gates   map[string]*companyGate
	closed  bool
}

type ManagerOptions struct {
	MaxOpenDatabases int
	DatabaseIdleTTL  time.Duration
	MaxOpenConns     int
	MaxIdleConns     int
	ConnMaxIdleTime  time.Duration
	ConnMaxLifetime  time.Duration
}

type databaseEntry struct {
	db       *sql.DB
	active   int
	lastUsed time.Time
}

type companyGate struct {
	mu   sync.Mutex
	refs int
}

type Lease struct {
	DB             *sql.DB
	manager        *Manager
	companyID      string
	entry          *databaseEntry
	touchOnRelease bool
	once           sync.Once
}

func (l *Lease) Release() {
	if l == nil {
		return
	}
	l.once.Do(func() {
		l.manager.mu.Lock()
		if current := l.manager.dbs[l.companyID]; current == l.entry {
			if current.active > 0 {
				current.active--
			}
			if l.touchOnRelease {
				current.lastUsed = time.Now()
			}
		}
		shouldPrune := len(l.manager.dbs) > l.manager.options.MaxOpenDatabases
		l.manager.mu.Unlock()
		if shouldPrune {
			_, _ = l.manager.PruneIdle(time.Now())
		}
	})
}

type Company struct {
	CompanyID       string `json:"companyId"`
	Description     string `json:"description"`
	AuthorizedFrom  int64  `json:"authorizedFrom"`
	AuthorizedUntil int64  `json:"authorizedUntil"`
	PackLimit       int64  `json:"packLimit"`
	DeliveryLimit   int64  `json:"deliveryLimit"`
	PackCount       int64  `json:"packCount"`
	DeliveryCount   int64  `json:"deliveryCount"`
	Status          string `json:"status"`
	ExtJSON         string `json:"extJson"`
	CreatedAt       int64  `json:"createdAt"`
	UpdatedAt       int64  `json:"updatedAt"`
}

type CreateCompanyInput struct {
	CompanyID       string
	Description     string
	AuthorizedFrom  int64
	AuthorizedUntil int64
	PackLimit       int64
	DeliveryLimit   int64
	ExtJSON         string
	APIKeyHash      string
}

type Release struct {
	ReleaseID                string   `json:"releaseId"`
	PayloadID                string   `json:"payloadId"`
	PayloadVersion           int64    `json:"payloadVersion"`
	PackageName              string   `json:"packageName"`
	VersionCode              int64    `json:"versionCode"`
	CertificateSHA256Digests []string `json:"certificateSha256Digests"`
	CertificateDigestsJSON   string   `json:"-"`
	CertificateSetSHA256     string   `json:"certificateSetSha256"`
	BusinessDexSHA256        string   `json:"businessDexSha256"`
	ResourcesSHA256          string   `json:"resourcesSha256"`
	NativeLibsSHA256         string   `json:"nativeLibsSha256"`
	ReleaseBuildSHA256       string   `json:"releaseBuildSha256"`
	PlaintextSHA256          string   `json:"plaintextSha256"`
	LocalCiphertextSHA256    string   `json:"localCiphertextSha256"`
	LocalPayloadSize         int64    `json:"localPayloadSize"`
	PayloadKeyCiphertext     []byte   `json:"-"`
	PayloadKeyVersion        int64    `json:"payloadKeyVersion"`
	Packer                   string   `json:"packer"`
	DeliveryCount            int64    `json:"deliveryCount"`
	DraftDeliveryCharged     bool     `json:"-"`
	Status                   string   `json:"status"`
	CreatedAt                int64    `json:"createdAt"`
	UpdatedAt                int64    `json:"updatedAt"`
	PublishedAt              int64    `json:"publishedAt,omitempty"`
	RevokedAt                int64    `json:"revokedAt,omitempty"`
}

type NewRelease struct {
	Release
}

func NewManager(dir string) (*Manager, error) {
	return NewManagerWithOptions(dir, ManagerOptions{})
}

func NewManagerWithOptions(dir string, options ManagerOptions) (*Manager, error) {
	abs, err := filepath.Abs(dir)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(abs, 0o750); err != nil {
		return nil, err
	}
	options = normalizeManagerOptions(options)
	return &Manager{dir: abs, options: options, dbs: make(map[string]*databaseEntry), gates: make(map[string]*companyGate)}, nil
}

func normalizeManagerOptions(options ManagerOptions) ManagerOptions {
	if options.MaxOpenDatabases <= 0 {
		options.MaxOpenDatabases = 128
	}
	if options.DatabaseIdleTTL <= 0 {
		options.DatabaseIdleTTL = 15 * time.Minute
	}
	if options.MaxOpenConns <= 0 {
		options.MaxOpenConns = 2
	}
	if options.MaxIdleConns <= 0 || options.MaxIdleConns > options.MaxOpenConns {
		options.MaxIdleConns = 1
	}
	if options.ConnMaxIdleTime <= 0 {
		options.ConnMaxIdleTime = 5 * time.Minute
	}
	if options.ConnMaxLifetime <= 0 {
		options.ConnMaxLifetime = 30 * time.Minute
	}
	return options
}

func ValidCompanyID(companyID string) bool { return companyIDPattern.MatchString(companyID) }

func (m *Manager) companyPath(companyID string) (string, error) {
	if !ValidCompanyID(companyID) {
		return "", errors.New("companyId must match [A-Za-z0-9][A-Za-z0-9_-]{1,63}")
	}
	return filepath.Join(m.dir, companyID+".db"), nil
}

func (m *Manager) CreateCompany(ctx context.Context, input CreateCompanyInput) (*Lease, error) {
	path, err := m.companyPath(input.CompanyID)
	if err != nil {
		return nil, err
	}
	unlock := m.lockCompany(input.CompanyID)
	defer unlock()
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return nil, errors.New("storage manager is closed")
	}
	_, alreadyOpen := m.dbs[input.CompanyID]
	m.mu.Unlock()
	if alreadyOpen {
		return nil, ErrConflict
	}
	if _, err := os.Stat(path); err == nil {
		return nil, ErrConflict
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	db, err := openSQLite(path, m.options)
	if err != nil {
		return nil, err
	}
	if err := initializeSchema(ctx, db); err != nil {
		db.Close()
		_ = os.Remove(path)
		return nil, err
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		db.Close()
		_ = os.Remove(path)
		return nil, err
	}
	defer tx.Rollback()
	now := time.Now().Unix()
	_, err = tx.ExecContext(ctx, `INSERT INTO company_info
		(company_id, description, authorized_from, authorized_until, pack_limit, delivery_limit, status, ext_json, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)`, input.CompanyID, input.Description,
		input.AuthorizedFrom, input.AuthorizedUntil, input.PackLimit, input.DeliveryLimit, input.ExtJSON, now, now)
	if err == nil {
		_, err = tx.ExecContext(ctx, `INSERT INTO company_api_keys (key_hash, description, status, created_at)
			VALUES (?, 'initial company API key', 'ACTIVE', ?)`, input.APIKeyHash, now)
	}
	if err == nil {
		err = tx.Commit()
	}
	if err != nil {
		db.Close()
		_ = os.Remove(path)
		return nil, err
	}
	entry := &databaseEntry{db: db, active: 1, lastUsed: time.Now()}
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		db.Close()
		return nil, errors.New("storage manager is closed")
	}
	m.dbs[input.CompanyID] = entry
	m.mu.Unlock()
	return &Lease{DB: db, manager: m, companyID: input.CompanyID, entry: entry, touchOnRelease: true}, nil
}

func (m *Manager) Acquire(ctx context.Context, companyID string) (*Lease, error) {
	return m.acquire(ctx, companyID, true, false)
}

func (m *Manager) acquire(ctx context.Context, companyID string, touch, cachedOnly bool) (*Lease, error) {
	path, err := m.companyPath(companyID)
	if err != nil {
		return nil, err
	}
	unlock := m.lockCompany(companyID)
	defer unlock()
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return nil, errors.New("storage manager is closed")
	}
	if entry := m.dbs[companyID]; entry != nil {
		entry.active++
		if touch {
			entry.lastUsed = time.Now()
		}
		m.mu.Unlock()
		return &Lease{DB: entry.db, manager: m, companyID: companyID, entry: entry, touchOnRelease: touch}, nil
	}
	m.mu.Unlock()
	if cachedOnly {
		return nil, ErrNotFound
	}
	if _, err := os.Stat(path); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	db, err := openSQLite(path, m.options)
	if err != nil {
		return nil, err
	}
	if err := initializeSchema(ctx, db); err != nil {
		db.Close()
		return nil, err
	}
	entry := &databaseEntry{db: db, active: 1, lastUsed: time.Now()}
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		db.Close()
		return nil, errors.New("storage manager is closed")
	}
	m.dbs[companyID] = entry
	m.mu.Unlock()
	return &Lease{DB: db, manager: m, companyID: companyID, entry: entry, touchOnRelease: touch}, nil
}

func (m *Manager) lockCompany(companyID string) func() {
	m.mu.Lock()
	gate := m.gates[companyID]
	if gate == nil {
		gate = &companyGate{}
		m.gates[companyID] = gate
	}
	gate.refs++
	m.mu.Unlock()
	gate.mu.Lock()
	return func() {
		gate.mu.Unlock()
		m.mu.Lock()
		gate.refs--
		if gate.refs == 0 {
			delete(m.gates, companyID)
		}
		m.mu.Unlock()
	}
}

func (m *Manager) CompanyIDs() ([]string, error) {
	entries, err := os.ReadDir(m.dir)
	if err != nil {
		return nil, err
	}
	ids := make([]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() && strings.EqualFold(filepath.Ext(entry.Name()), ".db") {
			id := strings.TrimSuffix(entry.Name(), filepath.Ext(entry.Name()))
			if ValidCompanyID(id) {
				ids = append(ids, id)
			}
		}
	}
	sort.Strings(ids)
	return ids, nil
}

func (m *Manager) CompanyIDsPage(page, pageSize int) ([]string, int, error) {
	ids, err := m.CompanyIDs()
	if err != nil {
		return nil, 0, err
	}
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	total := len(ids)
	if total == 0 || page > (total-1)/pageSize+1 {
		return []string{}, total, nil
	}
	start := (page - 1) * pageSize
	end := start + pageSize
	if end > total {
		end = total
	}
	return ids[start:end], total, nil
}

func (m *Manager) PruneIdle(now time.Time) (int, error) {
	type candidate struct {
		companyID string
		lastUsed  time.Time
	}
	m.mu.Lock()
	candidates := make([]candidate, 0, len(m.dbs))
	for companyID, entry := range m.dbs {
		if entry.active == 0 {
			candidates = append(candidates, candidate{companyID: companyID, lastUsed: entry.lastUsed})
		}
	}
	m.mu.Unlock()
	sort.Slice(candidates, func(i, j int) bool { return candidates[i].lastUsed.Before(candidates[j].lastUsed) })

	closed := 0
	var closeErr error
	for _, candidate := range candidates {
		unlock := m.lockCompany(candidate.companyID)
		m.mu.Lock()
		entry := m.dbs[candidate.companyID]
		shouldClose := entry != nil && entry.active == 0 &&
			(now.Sub(entry.lastUsed) >= m.options.DatabaseIdleTTL || len(m.dbs) > m.options.MaxOpenDatabases)
		if shouldClose {
			delete(m.dbs, candidate.companyID)
		}
		m.mu.Unlock()
		if shouldClose {
			if err := entry.db.Close(); err != nil {
				closeErr = errors.Join(closeErr, err)
			}
			closed++
		}
		unlock()
	}
	return closed, closeErr
}

func (m *Manager) CleanupExpiredChallenges(ctx context.Context, batchSize int) (int64, int, error) {
	if batchSize <= 0 {
		return 0, 0, errors.New("challenge cleanup batch size must be positive")
	}
	m.mu.Lock()
	companyIDs := make([]string, 0, len(m.dbs))
	for companyID := range m.dbs {
		companyIDs = append(companyIDs, companyID)
	}
	m.mu.Unlock()
	sort.Strings(companyIDs)

	var deleted int64
	cleanedDatabases := 0
	var cleanupErr error
	cutoff := time.Now().Unix()
	for _, companyID := range companyIDs {
		if err := ctx.Err(); err != nil {
			return deleted, cleanedDatabases, errors.Join(cleanupErr, err)
		}
		lease, err := m.acquire(ctx, companyID, false, true)
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			cleanupErr = errors.Join(cleanupErr, fmt.Errorf("cleanup challenges for %s: %w", companyID, err))
			continue
		}
		result, err := lease.DB.ExecContext(ctx, `DELETE FROM challenges WHERE challenge_id IN (
			SELECT challenge_id FROM challenges WHERE expires_at < ? ORDER BY expires_at LIMIT ?
		)`, cutoff, batchSize)
		lease.Release()
		if err != nil {
			cleanupErr = errors.Join(cleanupErr, fmt.Errorf("cleanup challenges for %s: %w", companyID, err))
			continue
		}
		rows, err := result.RowsAffected()
		if err != nil {
			cleanupErr = errors.Join(cleanupErr, err)
			continue
		}
		deleted += rows
		cleanedDatabases++
	}
	return deleted, cleanedDatabases, cleanupErr
}

func (m *Manager) Close() {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return
	}
	m.closed = true
	entries := make([]*databaseEntry, 0, len(m.dbs))
	for id, entry := range m.dbs {
		entries = append(entries, entry)
		delete(m.dbs, id)
	}
	m.mu.Unlock()
	for _, entry := range entries {
		_ = entry.db.Close()
	}
}

func openSQLite(path string, optionValues ...ManagerOptions) (*sql.DB, error) {
	options := normalizeManagerOptions(ManagerOptions{})
	if len(optionValues) > 0 {
		options = normalizeManagerOptions(optionValues[0])
	}
	dsn := "file:" + filepath.ToSlash(path) + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(ON)&_pragma=synchronous(NORMAL)&_txlock=immediate"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(options.MaxOpenConns)
	db.SetMaxIdleConns(options.MaxIdleConns)
	db.SetConnMaxIdleTime(options.ConnMaxIdleTime)
	db.SetConnMaxLifetime(options.ConnMaxLifetime)
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

func GetCompany(ctx context.Context, db *sql.DB) (Company, error) {
	var value Company
	err := db.QueryRowContext(ctx, `SELECT company_id, description, authorized_from, authorized_until,
		pack_limit, delivery_limit, pack_count, delivery_count, status, ext_json, created_at, updated_at
		FROM company_info LIMIT 1`).Scan(&value.CompanyID, &value.Description, &value.AuthorizedFrom,
		&value.AuthorizedUntil, &value.PackLimit, &value.DeliveryLimit, &value.PackCount,
		&value.DeliveryCount, &value.Status, &value.ExtJSON, &value.CreatedAt, &value.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Company{}, ErrNotFound
	}
	return value, err
}

func UpdateCompany(ctx context.Context, db *sql.DB, description string, authorizedFrom, authorizedUntil, packLimit, deliveryLimit int64, status, extJSON string) error {
	result, err := db.ExecContext(ctx, `UPDATE company_info SET description=?, authorized_from=?, authorized_until=?,
		pack_limit=?, delivery_limit=?, status=?, ext_json=?, updated_at=?`, description, authorizedFrom,
		authorizedUntil, packLimit, deliveryLimit, status, extJSON, time.Now().Unix())
	if err != nil {
		return err
	}
	rows, _ := result.RowsAffected()
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

func AuthenticateCompany(ctx context.Context, db *sql.DB, keyHash string) error {
	var found int
	err := db.QueryRowContext(ctx, `SELECT 1 FROM company_api_keys WHERE key_hash=? AND status='ACTIVE' LIMIT 1`, keyHash).Scan(&found)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrUnauthorized
	}
	if err != nil {
		return err
	}
	return nil
}

func CreateRelease(ctx context.Context, db *sql.DB, release NewRelease) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var status string
	var from, until, limit, count int64
	if err := tx.QueryRowContext(ctx, `SELECT status, authorized_from, authorized_until, pack_limit, pack_count FROM company_info LIMIT 1`).
		Scan(&status, &from, &until, &limit, &count); err != nil {
		return err
	}
	now := time.Now().Unix()
	if status != "ACTIVE" || now < from || (until > 0 && now > until) {
		return ErrAuthorization
	}
	if limit > 0 && count >= limit {
		return ErrLimitExceeded
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO payload_releases
		(release_id, payload_id, payload_version, package_name, version_code,
		 certificate_sha256_digests_json, certificate_set_sha256, business_dex_sha256,
		 resources_sha256, native_libs_sha256, release_build_sha256, plaintext_sha256,
		 local_ciphertext_sha256, local_payload_size, payload_key_ciphertext,
		 payload_key_version, packer, status, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)`,
		release.ReleaseID, release.PayloadID, release.PayloadVersion, release.PackageName,
		release.VersionCode, release.CertificateDigestsJSON, release.CertificateSetSHA256,
		release.BusinessDexSHA256, release.ResourcesSHA256, release.NativeLibsSHA256,
		release.ReleaseBuildSHA256, release.PlaintextSHA256, release.LocalCiphertextSHA256,
		release.LocalPayloadSize, release.PayloadKeyCiphertext, release.PayloadKeyVersion,
		release.Packer, now, now)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "unique") {
			return ErrConflict
		}
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE company_info SET pack_count=pack_count+1, updated_at=?`, now); err != nil {
		return err
	}
	return tx.Commit()
}

func UpdateRelease(ctx context.Context, db *sql.DB, release NewRelease, expectedKeyVersion int64) error {
	now := time.Now().Unix()
	result, err := db.ExecContext(ctx, `UPDATE payload_releases SET
		payload_id=?, payload_version=?, certificate_sha256_digests_json=?,
		certificate_set_sha256=?, business_dex_sha256=?, resources_sha256=?,
		native_libs_sha256=?, release_build_sha256=?, plaintext_sha256=?,
		local_ciphertext_sha256=?, local_payload_size=?, payload_key_ciphertext=?,
		payload_key_version=?, packer=?, updated_at=?
		WHERE release_id=? AND status='DRAFT' AND payload_key_version=?`,
		release.PayloadID, release.PayloadVersion, release.CertificateDigestsJSON,
		release.CertificateSetSHA256, release.BusinessDexSHA256, release.ResourcesSHA256,
		release.NativeLibsSHA256, release.ReleaseBuildSHA256, release.PlaintextSHA256,
		release.LocalCiphertextSHA256, release.LocalPayloadSize, release.PayloadKeyCiphertext,
		release.PayloadKeyVersion, release.Packer, now,
		release.ReleaseID, expectedKeyVersion)
	if err != nil {
		return err
	}
	rows, _ := result.RowsAffected()
	if rows == 0 {
		return ErrConflict
	}
	return nil
}

func ListReleasesPage(ctx context.Context, db *sql.DB, beforeCreatedAt int64, beforeReleaseID string, pageSize int) ([]Release, bool, error) {
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	query := releaseMetadataSelect
	args := make([]any, 0, 4)
	if beforeCreatedAt > 0 && beforeReleaseID != "" {
		query += ` WHERE created_at < ? OR (created_at = ? AND release_id < ?)`
		args = append(args, beforeCreatedAt, beforeCreatedAt, beforeReleaseID)
	}
	query += ` ORDER BY created_at DESC, release_id DESC LIMIT ?`
	args = append(args, pageSize+1)
	rows, err := db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, false, err
	}
	defer rows.Close()
	values := make([]Release, 0, pageSize+1)
	for rows.Next() {
		value, err := scanReleaseMetadata(rows)
		if err != nil {
			return nil, false, err
		}
		values = append(values, value)
	}
	if err := rows.Err(); err != nil {
		return nil, false, err
	}
	hasMore := len(values) > pageSize
	if hasMore {
		values = values[:pageSize]
	}
	return values, hasMore, nil
}

func ListPackLogs(ctx context.Context, db *sql.DB, page, pageSize int) ([]Release, int, error) {
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 5
	}
	offset := (page - 1) * pageSize

	var total int
	if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM payload_releases`).Scan(&total); err != nil {
		return nil, 0, err
	}

	query := `SELECT release_id, payload_id, payload_version, package_name, version_code,
		certificate_sha256_digests_json, certificate_set_sha256, business_dex_sha256,
		resources_sha256, native_libs_sha256, release_build_sha256, plaintext_sha256,
		local_ciphertext_sha256, local_payload_size, payload_key_version, packer, delivery_count,
		status, created_at, updated_at, published_at, revoked_at
		FROM payload_releases
		ORDER BY created_at DESC
		LIMIT ? OFFSET ?`

	rows, err := db.QueryContext(ctx, query, pageSize, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var logs []Release
	for rows.Next() {
		var log Release
		var certsJSON string
		err := rows.Scan(&log.ReleaseID, &log.PayloadID, &log.PayloadVersion, &log.PackageName,
			&log.VersionCode, &certsJSON, &log.CertificateSetSHA256,
			&log.BusinessDexSHA256, &log.ResourcesSHA256, &log.NativeLibsSHA256,
			&log.ReleaseBuildSHA256, &log.PlaintextSHA256, &log.LocalCiphertextSHA256, &log.LocalPayloadSize,
			&log.PayloadKeyVersion, &log.Packer, &log.DeliveryCount, &log.Status,
			&log.CreatedAt, &log.UpdatedAt, &log.PublishedAt, &log.RevokedAt)
		if err != nil {
			return nil, 0, err
		}
		_ = json.Unmarshal([]byte(certsJSON), &log.CertificateSHA256Digests)
		logs = append(logs, log)
	}
	return logs, total, nil
}

func GetRelease(ctx context.Context, db *sql.DB, releaseID string, publishedOnly bool) (Release, error) {
	query := releaseSelect + ` WHERE release_id=?`
	if publishedOnly {
		query += ` AND status='PUBLISHED'`
	}
	value, err := scanRelease(db.QueryRowContext(ctx, query, releaseID))
	if errors.Is(err, sql.ErrNoRows) {
		return Release{}, ErrNotFound
	}
	return value, err
}

func GetReleaseMetadata(ctx context.Context, db *sql.DB, releaseID string) (Release, error) {
	value, err := scanReleaseMetadata(db.QueryRowContext(ctx, releaseMetadataSelect+` WHERE release_id=?`, releaseID))
	if errors.Is(err, sql.ErrNoRows) {
		return Release{}, ErrNotFound
	}
	return value, err
}

func GetReleaseByVersion(ctx context.Context, db *sql.DB, packageName string, versionCode int64) (Release, error) {
	value, err := scanRelease(db.QueryRowContext(ctx, releaseSelect+` WHERE package_name=? AND version_code=?`,
		packageName, versionCode))
	if errors.Is(err, sql.ErrNoRows) {
		return Release{}, ErrNotFound
	}
	return value, err
}

func UpdateReleasePacker(ctx context.Context, db *sql.DB, releaseID, packer string) error {
	if packer == "" {
		return nil
	}
	result, err := db.ExecContext(ctx, `UPDATE payload_releases SET packer=?, updated_at=?
		WHERE release_id=? AND status='DRAFT' AND packer<>?`,
		packer, time.Now().Unix(), releaseID, packer)
	if err != nil {
		return err
	}
	_, _ = result.RowsAffected()
	return nil
}

func SetReleaseStatus(ctx context.Context, db *sql.DB, releaseID, status string) (string, bool, error) {
	release, err := GetReleaseMetadata(ctx, db, releaseID)
	if err != nil {
		return "", false, err
	}

	now := time.Now().Unix()
	var result sql.Result
	switch status {
	case "PUBLISHED":
		if release.LocalCiphertextSHA256 == "" || release.LocalPayloadSize <= 0 {
			return "", false, fmt.Errorf("%w: local payload is not sealed", ErrInvalidTransition)
		}
		switch release.Status {
		case "DRAFT":
			result, err = db.ExecContext(ctx, `UPDATE payload_releases SET status=?, published_at=?, updated_at=?
				WHERE release_id=? AND status='DRAFT'`, status, now, now, releaseID)
		case "PUBLISHED":
			return release.Packer, false, nil
		case "REVOKED":
			return "", false, fmt.Errorf("%w: REVOKED to PUBLISHED", ErrInvalidTransition)
		default:
			return "", false, fmt.Errorf("%w: unsupported current status %q", ErrInvalidTransition, release.Status)
		}
	case "REVOKED":
		switch release.Status {
		case "DRAFT", "PUBLISHED":
			result, err = db.ExecContext(ctx, `UPDATE payload_releases SET status=?, revoked_at=?, updated_at=?
				WHERE release_id=? AND status=?`, status, now, now, releaseID, release.Status)
		case "REVOKED":
			return "", false, ErrAlreadyRevoked
		default:
			return "", false, fmt.Errorf("%w: unsupported current status %q", ErrInvalidTransition, release.Status)
		}
	default:
		return "", false, fmt.Errorf("%w: unsupported target status %q", ErrInvalidTransition, status)
	}
	if err != nil {
		return "", false, err
	}
	rows, _ := result.RowsAffected()
	if rows == 0 {
		// A concurrent publisher may have completed the same desired transition
		// after the initial read. Treat that race as the same idempotent success.
		if status == "PUBLISHED" {
			current, currentErr := GetReleaseMetadata(ctx, db, releaseID)
			if currentErr == nil && current.Status == "PUBLISHED" {
				return current.Packer, false, nil
			}
		}
		return "", false, ErrConflict
	}
	return release.Packer, true, nil
}

func SealLocalPayload(ctx context.Context, db *sql.DB, releaseID, ciphertextSHA256 string, payloadSize int64) error {
	if ciphertextSHA256 == "" || payloadSize <= 0 {
		return ErrConflict
	}
	release, err := GetReleaseMetadata(ctx, db, releaseID)
	if err != nil {
		return err
	}
	if release.Status == "REVOKED" {
		return fmt.Errorf("%w: revoked release cannot be sealed", ErrInvalidTransition)
	}
	if release.LocalCiphertextSHA256 != "" {
		if release.LocalCiphertextSHA256 == ciphertextSHA256 && release.LocalPayloadSize == payloadSize {
			return nil
		}
		return ErrConflict
	}
	if release.Status != "DRAFT" {
		return fmt.Errorf("%w: only a draft release can be sealed", ErrInvalidTransition)
	}
	result, err := db.ExecContext(ctx, `UPDATE payload_releases
		SET local_ciphertext_sha256=?, local_payload_size=?, updated_at=?
		WHERE release_id=? AND status='DRAFT' AND local_ciphertext_sha256=''`,
		ciphertextSHA256, payloadSize, time.Now().Unix(), releaseID)
	if err != nil {
		return err
	}
	if rows, _ := result.RowsAffected(); rows != 1 {
		return ErrConflict
	}
	return nil
}

func CreateChallenge(ctx context.Context, db *sql.DB, id, challenge, purpose string, expiresAt int64) error {
	now := time.Now().Unix()
	_, err := db.ExecContext(ctx, `INSERT INTO challenges (challenge_id, challenge, purpose, expires_at, created_at)
		VALUES (?, ?, ?, ?, ?)`, id, challenge, purpose, expiresAt, now)
	return err
}

func ConsumeChallenge(ctx context.Context, db *sql.DB, id, challenge, purpose string) error {
	now := time.Now().Unix()
	result, err := db.ExecContext(ctx, `UPDATE challenges SET used_at=? WHERE challenge_id=? AND challenge=?
		AND purpose=? AND used_at=0 AND expires_at>=?`, now, id, challenge, purpose, now)
	if err != nil {
		return err
	}
	rows, _ := result.RowsAffected()
	if rows != 1 {
		return ErrChallengeUsed
	}
	return nil
}

func IsRevoked(ctx context.Context, db *sql.DB, targetType, targetHash string) (bool, error) {
	now := time.Now().Unix()
	var found int
	err := db.QueryRowContext(ctx, `SELECT 1 FROM revocations WHERE target_type=? AND target_hash=?
		AND effective_at<=? AND (expires_at=0 OR expires_at>?) LIMIT 1`, targetType, targetHash, now, now).Scan(&found)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	return err == nil, err
}

func AddRevocation(ctx context.Context, db *sql.DB, id, targetType, targetHash, reason string, expiresAt int64) error {
	_, err := db.ExecContext(ctx, `INSERT INTO revocations
		(revocation_id, target_type, target_hash, reason, effective_at, expires_at, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`, id, targetType, targetHash, reason, time.Now().Unix(), expiresAt, time.Now().Unix())
	return err
}

func IncrementDelivery(ctx context.Context, db *sql.DB, releaseID string) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var limit, count, authorizedFrom, authorizedUntil int64
	var status string
	if err := tx.QueryRowContext(ctx, `SELECT delivery_limit, delivery_count, status, authorized_from, authorized_until FROM company_info LIMIT 1`).
		Scan(&limit, &count, &status, &authorizedFrom, &authorizedUntil); err != nil {
		return err
	}
	now := time.Now().Unix()
	if status != "ACTIVE" || now < authorizedFrom || (authorizedUntil > 0 && now > authorizedUntil) {
		return ErrAuthorization
	}
	var releaseStatus string
	var draftCharged bool
	if err := tx.QueryRowContext(ctx, `SELECT status, draft_delivery_charged FROM payload_releases WHERE release_id=?`, releaseID).
		Scan(&releaseStatus, &draftCharged); errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	} else if err != nil {
		return err
	}
	chargeQuota := releaseStatus != "DRAFT" || !draftCharged
	if chargeQuota && limit > 0 && count >= limit {
		return ErrLimitExceeded
	}
	result, err := tx.ExecContext(ctx, `UPDATE payload_releases SET
		delivery_count=delivery_count+1,
		draft_delivery_charged=CASE WHEN status='DRAFT' THEN 1 ELSE draft_delivery_charged END
		WHERE release_id=? AND status=? AND draft_delivery_charged=?`, releaseID, releaseStatus, draftCharged)
	if err != nil {
		return err
	}
	if rows, _ := result.RowsAffected(); rows != 1 {
		return ErrConflict
	}
	if chargeQuota {
		if _, err := tx.ExecContext(ctx, `UPDATE company_info SET delivery_count=delivery_count+1, updated_at=?`, now); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func AddOperationLog(ctx context.Context, db *sql.DB, operation, requestID, detail, packer string) {
	_, _ = db.ExecContext(ctx, `INSERT INTO operation_logs (operation, result, request_id, detail, packer, created_at)
		VALUES (?, 'SUCCESS', ?, ?, ?, ?)`, operation, requestID, detail, packer, time.Now().Unix())
}

const releaseSelect = `SELECT release_id, payload_id, payload_version, package_name, version_code,
	certificate_sha256_digests_json, certificate_set_sha256, business_dex_sha256,
	resources_sha256, native_libs_sha256, release_build_sha256, plaintext_sha256,
	local_ciphertext_sha256, local_payload_size, payload_key_ciphertext,
	payload_key_version, packer, delivery_count, draft_delivery_charged,
	status, created_at, updated_at, published_at, revoked_at
	FROM payload_releases`

const releaseMetadataSelect = `SELECT release_id, payload_id, payload_version, package_name, version_code,
	certificate_sha256_digests_json, certificate_set_sha256, business_dex_sha256,
	resources_sha256, native_libs_sha256, release_build_sha256, plaintext_sha256,
	local_ciphertext_sha256, local_payload_size, payload_key_version, packer, delivery_count, draft_delivery_charged,
	status, created_at, updated_at, published_at, revoked_at
	FROM payload_releases`

type scanner interface{ Scan(...any) error }

func scanRelease(row scanner) (Release, error) {
	var value Release
	err := row.Scan(&value.ReleaseID, &value.PayloadID, &value.PayloadVersion, &value.PackageName,
		&value.VersionCode, &value.CertificateDigestsJSON, &value.CertificateSetSHA256,
		&value.BusinessDexSHA256, &value.ResourcesSHA256, &value.NativeLibsSHA256,
		&value.ReleaseBuildSHA256, &value.PlaintextSHA256, &value.LocalCiphertextSHA256,
		&value.LocalPayloadSize, &value.PayloadKeyCiphertext, &value.PayloadKeyVersion,
		&value.Packer, &value.DeliveryCount, &value.DraftDeliveryCharged, &value.Status,
		&value.CreatedAt, &value.UpdatedAt, &value.PublishedAt, &value.RevokedAt)
	if err == nil {
		err = json.Unmarshal([]byte(value.CertificateDigestsJSON), &value.CertificateSHA256Digests)
	}
	return value, err
}

func scanReleaseMetadata(row scanner) (Release, error) {
	var value Release
	err := row.Scan(&value.ReleaseID, &value.PayloadID, &value.PayloadVersion, &value.PackageName,
		&value.VersionCode, &value.CertificateDigestsJSON, &value.CertificateSetSHA256,
		&value.BusinessDexSHA256, &value.ResourcesSHA256, &value.NativeLibsSHA256,
		&value.ReleaseBuildSHA256, &value.PlaintextSHA256, &value.LocalCiphertextSHA256, &value.LocalPayloadSize,
		&value.PayloadKeyVersion, &value.Packer, &value.DeliveryCount, &value.DraftDeliveryCharged,
		&value.Status, &value.CreatedAt, &value.UpdatedAt, &value.PublishedAt, &value.RevokedAt)
	if err == nil {
		err = json.Unmarshal([]byte(value.CertificateDigestsJSON), &value.CertificateSHA256Digests)
	}
	return value, err
}

func initializeSchema(ctx context.Context, db *sql.DB) error {
	if _, err := db.ExecContext(ctx, schemaSQL); err != nil {
		return fmt.Errorf("initialize company schema: %w", err)
	}
	if err := validateSchemaV6(ctx, db); err != nil {
		return fmt.Errorf("validate company schema: %w", err)
	}
	for table, description := range tableDescriptions {
		if _, err := db.ExecContext(ctx, `INSERT INTO schema_descriptions(table_name, description)
			VALUES (?, ?) ON CONFLICT(table_name) DO UPDATE SET description=excluded.description`, table, description); err != nil {
			return err
		}
	}
	return nil
}

func validateSchemaV6(ctx context.Context, db *sql.DB) error {
	var version, requiredColumns int
	if err := db.QueryRowContext(ctx, `SELECT schema_version FROM schema_meta LIMIT 1`).Scan(&version); err != nil {
		return err
	}
	if version != 6 {
		return fmt.Errorf("schema version %d is unsupported; version 6 is required", version)
	}
	if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM pragma_table_info('payload_releases')
		WHERE name IN ('local_ciphertext_sha256','local_payload_size','payload_key_ciphertext')`).
		Scan(&requiredColumns); err != nil {
		return err
	}
	if requiredColumns != 3 {
		return errors.New("schema version 6 payload_releases columns are incomplete")
	}
	return nil
}

const schemaSQL = `
CREATE TABLE IF NOT EXISTS schema_meta (
    schema_version INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
INSERT INTO schema_meta(schema_version, updated_at)
SELECT 6, unixepoch() WHERE NOT EXISTS (SELECT 1 FROM schema_meta);

CREATE TABLE IF NOT EXISTS schema_descriptions (
    table_name TEXT PRIMARY KEY,
    description TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS company_info (
    company_id TEXT PRIMARY KEY,
    description TEXT NOT NULL DEFAULT '',
    authorized_from INTEGER NOT NULL,
    authorized_until INTEGER NOT NULL DEFAULT 0,
    pack_limit INTEGER NOT NULL DEFAULT 0 CHECK(pack_limit >= 0),
    delivery_limit INTEGER NOT NULL DEFAULT 0 CHECK(delivery_limit >= 0),
    pack_count INTEGER NOT NULL DEFAULT 0 CHECK(pack_count >= 0),
    delivery_count INTEGER NOT NULL DEFAULT 0 CHECK(delivery_count >= 0),
    status TEXT NOT NULL CHECK(status IN ('ACTIVE','SUSPENDED','EXPIRED','REVOKED')),
    ext_json TEXT NOT NULL DEFAULT '{}',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS company_api_keys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    key_hash TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL CHECK(status IN ('ACTIVE','REVOKED')),
    created_at INTEGER NOT NULL,
    revoked_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS payload_releases (
    release_id TEXT PRIMARY KEY,
    payload_id TEXT NOT NULL,
    payload_version INTEGER NOT NULL,
    package_name TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    certificate_sha256_digests_json TEXT NOT NULL,
    certificate_set_sha256 TEXT NOT NULL,
    business_dex_sha256 TEXT NOT NULL,
    resources_sha256 TEXT NOT NULL,
    native_libs_sha256 TEXT NOT NULL,
    release_build_sha256 TEXT NOT NULL,
    plaintext_sha256 TEXT NOT NULL,
    local_ciphertext_sha256 TEXT NOT NULL DEFAULT '',
    local_payload_size INTEGER NOT NULL DEFAULT 0 CHECK(local_payload_size >= 0),
    payload_key_ciphertext BLOB NOT NULL,
    payload_key_version INTEGER NOT NULL DEFAULT 1,
    packer TEXT NOT NULL DEFAULT '' CHECK(length(packer) <= 64),
    delivery_count INTEGER NOT NULL DEFAULT 0 CHECK(delivery_count >= 0),
    draft_delivery_charged INTEGER NOT NULL DEFAULT 0 CHECK(draft_delivery_charged IN (0,1)),
    status TEXT NOT NULL CHECK(status IN ('DRAFT','PUBLISHED','REVOKED')),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    published_at INTEGER NOT NULL DEFAULT 0,
    revoked_at INTEGER NOT NULL DEFAULT 0,
    UNIQUE(package_name, version_code)
);
CREATE INDEX IF NOT EXISTS idx_payload_release_created ON payload_releases(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_payload_release_created_id ON payload_releases(created_at DESC, release_id DESC);

CREATE TABLE IF NOT EXISTS challenges (
    challenge_id TEXT PRIMARY KEY,
    challenge TEXT NOT NULL,
    purpose TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    used_at INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_challenge_expiry ON challenges(expires_at);

CREATE TABLE IF NOT EXISTS revocations (
    revocation_id TEXT PRIMARY KEY,
    target_type TEXT NOT NULL,
    target_hash TEXT NOT NULL,
    reason TEXT NOT NULL,
    effective_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_revocation_target ON revocations(target_type, target_hash, effective_at);

CREATE TABLE IF NOT EXISTS operation_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    operation TEXT NOT NULL CHECK(operation IN ('PACK_CREATE','PACK_PUBLISH','PACK_REVOKE','COMPANY_REVOKE')),
    result TEXT NOT NULL DEFAULT 'SUCCESS' CHECK(result='SUCCESS'),
    request_id TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    packer TEXT NOT NULL DEFAULT '' CHECK(length(packer) <= 64),
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_operation_created ON operation_logs(created_at);
`

var tableDescriptions = map[string]string{
	"schema_meta":         "记录当前公司数据库的结构版本，用于后续自动迁移。",
	"schema_descriptions": "保存每张表的中文用途说明，使 SQLite 文件自身具备可读的数据字典。",
	"company_info":        "保存该数据库所属公司的标识、说明、授权时间、打包/下发限额和累计次数，以及可扩展 JSON。",
	"company_api_keys":    "保存公司调用打包和管理接口所用 API Key 的 SHA-256 摘要，不保存明文 Key。",
	"payload_releases":    "保存公司已打包的 Payload 版本、身份绑定信息、标准加密 Payload 和被主密钥封装的标准 Payload Key。",
	"challenges":          "保存短期一次性挑战值，用于设备注册和解包授权的防重放校验；过期记录会自动清理。",
	"revocations":         "只保存被撤销的设备、公钥、证书、应用版本或 Payload，正常设备不产生长期记录。",
	"operation_logs":      "只保存打包创建、发布、撤销和公司撤销四类生命周期审计记录。",
}
