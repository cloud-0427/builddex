package store

import (
	"context"
	"database/sql"
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
	ErrNotFound      = errors.New("not found")
	ErrConflict      = errors.New("conflict")
	ErrUnauthorized  = errors.New("unauthorized")
	ErrLimitExceeded = errors.New("limit exceeded")
	ErrAuthorization = errors.New("company authorization is inactive")
	ErrChallengeUsed = errors.New("challenge is invalid, expired, or already used")
)

type Manager struct {
	dir string
	mu  sync.Mutex
	dbs map[string]*sql.DB
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
	ReleaseID              string `json:"releaseId"`
	PayloadID              string `json:"payloadId"`
	PayloadVersion         int64  `json:"payloadVersion"`
	PackageName            string `json:"packageName"`
	VersionCode            int64  `json:"versionCode"`
	CertificateSHA256      string `json:"certificateSha256"`
	PlaintextSHA256        string `json:"plaintextSha256"`
	CanonicalCipherSHA256  string `json:"canonicalCiphertextSha256"`
	CanonicalPayload       []byte `json:"-"`
	CanonicalKeyCiphertext []byte `json:"-"`
	PayloadKeyVersion      int64  `json:"payloadKeyVersion"`
	Status                 string `json:"status"`
	CreatedAt              int64  `json:"createdAt"`
	PublishedAt            int64  `json:"publishedAt,omitempty"`
	RevokedAt              int64  `json:"revokedAt,omitempty"`
}

type NewRelease struct {
	Release
}

func NewManager(dir string) (*Manager, error) {
	abs, err := filepath.Abs(dir)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(abs, 0o750); err != nil {
		return nil, err
	}
	return &Manager{dir: abs, dbs: make(map[string]*sql.DB)}, nil
}

func ValidCompanyID(companyID string) bool { return companyIDPattern.MatchString(companyID) }

func (m *Manager) companyPath(companyID string) (string, error) {
	if !ValidCompanyID(companyID) {
		return "", errors.New("companyId must match [A-Za-z0-9][A-Za-z0-9_-]{1,63}")
	}
	return filepath.Join(m.dir, companyID+".db"), nil
}

func (m *Manager) CreateCompany(ctx context.Context, input CreateCompanyInput) (*sql.DB, error) {
	path, err := m.companyPath(input.CompanyID)
	if err != nil {
		return nil, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, err := os.Stat(path); err == nil {
		return nil, ErrConflict
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	db, err := openSQLite(path)
	if err != nil {
		return nil, err
	}
	if err := initializeSchema(ctx, db); err != nil {
		db.Close()
		_ = os.Remove(path)
		return nil, err
	}
	now := time.Now().Unix()
	_, err = db.ExecContext(ctx, `INSERT INTO company_info
		(company_id, description, authorized_from, authorized_until, pack_limit, delivery_limit, status, ext_json, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)`, input.CompanyID, input.Description,
		input.AuthorizedFrom, input.AuthorizedUntil, input.PackLimit, input.DeliveryLimit, input.ExtJSON, now, now)
	if err == nil {
		_, err = db.ExecContext(ctx, `INSERT INTO company_api_keys (key_hash, description, status, created_at)
			VALUES (?, 'initial company API key', 'ACTIVE', ?)`, input.APIKeyHash, now)
	}
	if err != nil {
		db.Close()
		_ = os.Remove(path)
		return nil, err
	}
	m.dbs[input.CompanyID] = db
	return db, nil
}

func (m *Manager) Open(companyID string) (*sql.DB, error) {
	path, err := m.companyPath(companyID)
	if err != nil {
		return nil, err
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if db := m.dbs[companyID]; db != nil {
		return db, nil
	}
	if _, err := os.Stat(path); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	db, err := openSQLite(path)
	if err != nil {
		return nil, err
	}
	if err := initializeSchema(context.Background(), db); err != nil {
		db.Close()
		return nil, err
	}
	m.dbs[companyID] = db
	return db, nil
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

func (m *Manager) Close() {
	m.mu.Lock()
	defer m.mu.Unlock()
	for id, db := range m.dbs {
		_ = db.Close()
		delete(m.dbs, id)
	}
}

func openSQLite(path string) (*sql.DB, error) {
	dsn := "file:" + filepath.ToSlash(path) + "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(ON)&_pragma=synchronous(NORMAL)"
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(4)
	db.SetMaxIdleConns(2)
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
	var count int
	err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM company_api_keys WHERE key_hash=? AND status='ACTIVE'`, keyHash).Scan(&count)
	if err != nil {
		return err
	}
	if count != 1 {
		return ErrUnauthorized
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
		(release_id, payload_id, payload_version, package_name, version_code, certificate_sha256,
		 plaintext_sha256, canonical_ciphertext_sha256, canonical_payload, canonical_key_ciphertext,
		 payload_key_version, status, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)`, release.ReleaseID, release.PayloadID,
		release.PayloadVersion, release.PackageName, release.VersionCode, release.CertificateSHA256,
		release.PlaintextSHA256, release.CanonicalCipherSHA256, release.CanonicalPayload,
		release.CanonicalKeyCiphertext, release.PayloadKeyVersion, now)
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

func UpdateRelease(ctx context.Context, db *sql.DB, release NewRelease) error {
	now := time.Now().Unix()
	result, err := db.ExecContext(ctx, `UPDATE payload_releases SET
		release_id=?, package_name=?, version_code=?, certificate_sha256=?,
		plaintext_sha256=?, canonical_ciphertext_sha256=?, canonical_payload=?,
		canonical_key_ciphertext=?, payload_key_version=?, status='DRAFT', created_at=?
		WHERE payload_id=? AND payload_version=? AND status='DRAFT'`,
		release.ReleaseID, release.PackageName, release.VersionCode, release.CertificateSHA256,
		release.PlaintextSHA256, release.CanonicalCipherSHA256, release.CanonicalPayload,
		release.CanonicalKeyCiphertext, release.PayloadKeyVersion, now,
		release.PayloadID, release.PayloadVersion)
	if err != nil {
		return err
	}
	rows, _ := result.RowsAffected()
	if rows == 0 {
		return ErrConflict
	}
	return nil
}

func ListReleases(ctx context.Context, db *sql.DB) ([]Release, error) {
	rows, err := db.QueryContext(ctx, releaseSelect+` ORDER BY created_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var values []Release
	for rows.Next() {
		value, err := scanRelease(rows)
		if err != nil {
			return nil, err
		}
		value.CanonicalPayload = nil
		value.CanonicalKeyCiphertext = nil
		values = append(values, value)
	}
	return values, rows.Err()
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

func GetReleaseByVersion(ctx context.Context, db *sql.DB, packageName, payloadID string, payloadVersion int64) (Release, error) {
	value, err := scanRelease(db.QueryRowContext(ctx, releaseSelect+` WHERE package_name=? AND payload_id=? AND payload_version=?`,
		packageName, payloadID, payloadVersion))
	if errors.Is(err, sql.ErrNoRows) {
		return Release{}, ErrNotFound
	}
	return value, err
}

func SetReleaseStatus(ctx context.Context, db *sql.DB, releaseID, status string) error {
	now := time.Now().Unix()
	var result sql.Result
	var err error
	switch status {
	case "PUBLISHED":
		result, err = db.ExecContext(ctx, `UPDATE payload_releases SET status=?, published_at=? WHERE release_id=? AND status='DRAFT'`, status, now, releaseID)
	case "REVOKED":
		result, err = db.ExecContext(ctx, `UPDATE payload_releases SET status=?, revoked_at=? WHERE release_id=? AND status!='REVOKED'`, status, now, releaseID)
	default:
		return errors.New("invalid release status")
	}
	if err != nil {
		return err
	}
	rows, _ := result.RowsAffected()
	if rows == 0 {
		return ErrConflict
	}
	return nil
}

func CreateChallenge(ctx context.Context, db *sql.DB, id, challenge, purpose string, expiresAt int64) error {
	now := time.Now().Unix()
	_, _ = db.ExecContext(ctx, `DELETE FROM challenges WHERE expires_at < ?`, now-3600)
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
	var count int
	err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM revocations WHERE target_type=? AND target_hash=?
		AND effective_at<=? AND (expires_at=0 OR expires_at>?)`, targetType, targetHash, now, now).Scan(&count)
	return count > 0, err
}

func AddRevocation(ctx context.Context, db *sql.DB, id, targetType, targetHash, reason string, expiresAt int64) error {
	_, err := db.ExecContext(ctx, `INSERT INTO revocations
		(revocation_id, target_type, target_hash, reason, effective_at, expires_at, created_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`, id, targetType, targetHash, reason, time.Now().Unix(), expiresAt, time.Now().Unix())
	return err
}

func IncrementDelivery(ctx context.Context, db *sql.DB) error {
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
	if limit > 0 && count >= limit {
		return ErrLimitExceeded
	}
	if _, err := tx.ExecContext(ctx, `UPDATE company_info SET delivery_count=delivery_count+1, updated_at=?`, now); err != nil {
		return err
	}
	return tx.Commit()
}

func AddOperationLog(ctx context.Context, db *sql.DB, operation, result, requestID, detail string) {
	_, _ = db.ExecContext(ctx, `INSERT INTO operation_logs (operation, result, request_id, detail, created_at)
		VALUES (?, ?, ?, ?, ?)`, operation, result, requestID, detail, time.Now().Unix())
}

const releaseSelect = `SELECT release_id, payload_id, payload_version, package_name, version_code,
	certificate_sha256, plaintext_sha256, canonical_ciphertext_sha256, canonical_payload,
	canonical_key_ciphertext, payload_key_version, status, created_at, published_at, revoked_at
	FROM payload_releases`

type scanner interface{ Scan(...any) error }

func scanRelease(row scanner) (Release, error) {
	var value Release
	err := row.Scan(&value.ReleaseID, &value.PayloadID, &value.PayloadVersion, &value.PackageName,
		&value.VersionCode, &value.CertificateSHA256, &value.PlaintextSHA256,
		&value.CanonicalCipherSHA256, &value.CanonicalPayload, &value.CanonicalKeyCiphertext,
		&value.PayloadKeyVersion, &value.Status, &value.CreatedAt, &value.PublishedAt, &value.RevokedAt)
	return value, err
}

func initializeSchema(ctx context.Context, db *sql.DB) error {
	if err := migrateSchema(ctx, db); err != nil {
		return fmt.Errorf("migrate company schema: %w", err)
	}
	if _, err := db.ExecContext(ctx, schemaSQL); err != nil {
		return fmt.Errorf("initialize company schema: %w", err)
	}
	for table, description := range tableDescriptions {
		if _, err := db.ExecContext(ctx, `INSERT INTO schema_descriptions(table_name, description)
			VALUES (?, ?) ON CONFLICT(table_name) DO UPDATE SET description=excluded.description`, table, description); err != nil {
			return err
		}
	}
	return nil
}

func migrateSchema(ctx context.Context, db *sql.DB) error {
	var version int
	err := db.QueryRowContext(ctx, `SELECT schema_version FROM schema_meta LIMIT 1`).Scan(&version)
	if err != nil {
		if strings.Contains(err.Error(), "no such table") {
			return nil // New database, initializeSchema will handle it
		}
		return err
	}

	if version < 2 {
		tx, err := db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		defer tx.Rollback()

		// Migration from 1 to 2: Update UNIQUE constraint in payload_releases
		_, err = tx.ExecContext(ctx, `ALTER TABLE payload_releases RENAME TO payload_releases_old`)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `CREATE TABLE payload_releases (
			release_id TEXT PRIMARY KEY,
			payload_id TEXT NOT NULL,
			payload_version INTEGER NOT NULL,
			package_name TEXT NOT NULL,
			version_code INTEGER NOT NULL,
			certificate_sha256 TEXT NOT NULL,
			plaintext_sha256 TEXT NOT NULL,
			canonical_ciphertext_sha256 TEXT NOT NULL,
			canonical_payload BLOB NOT NULL,
			canonical_key_ciphertext BLOB NOT NULL,
			payload_key_version INTEGER NOT NULL DEFAULT 1,
			status TEXT NOT NULL CHECK(status IN ('DRAFT','PUBLISHED','REVOKED')),
			created_at INTEGER NOT NULL,
			published_at INTEGER NOT NULL DEFAULT 0,
			revoked_at INTEGER NOT NULL DEFAULT 0,
			UNIQUE(package_name, payload_id, payload_version)
		)`)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO payload_releases SELECT * FROM payload_releases_old`)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `DROP TABLE payload_releases_old`)
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, `UPDATE schema_meta SET schema_version=2, updated_at=unixepoch()`)
		if err != nil {
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}

const schemaSQL = `
CREATE TABLE IF NOT EXISTS schema_meta (
    schema_version INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
INSERT INTO schema_meta(schema_version, updated_at)
SELECT 2, unixepoch() WHERE NOT EXISTS (SELECT 1 FROM schema_meta);

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
    certificate_sha256 TEXT NOT NULL,
    plaintext_sha256 TEXT NOT NULL,
    canonical_ciphertext_sha256 TEXT NOT NULL,
    canonical_payload BLOB NOT NULL,
    canonical_key_ciphertext BLOB NOT NULL,
    payload_key_version INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL CHECK(status IN ('DRAFT','PUBLISHED','REVOKED')),
    created_at INTEGER NOT NULL,
    published_at INTEGER NOT NULL DEFAULT 0,
    revoked_at INTEGER NOT NULL DEFAULT 0,
    UNIQUE(package_name, payload_id, payload_version)
);
CREATE INDEX IF NOT EXISTS idx_payload_release_status ON payload_releases(status, version_code);

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
    operation TEXT NOT NULL,
    result TEXT NOT NULL,
    request_id TEXT NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
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
	"operation_logs":      "保存打包、发布、撤销和下发等关键操作的轻量审计记录，可按保留策略定期清理。",
}
