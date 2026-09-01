package httpapi

import (
	"context"
	"crypto/ed25519"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"hash/fnv"
	"net/http"
	"runtime/debug"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/xjc/jiagu/server/internal/config"
	"github.com/xjc/jiagu/server/internal/integrity"
	"github.com/xjc/jiagu/server/internal/secure"
	"github.com/xjc/jiagu/server/internal/store"
	"go.uber.org/zap"
)

type API struct {
	cfg       config.Config
	dbs       *store.Manager
	integrity integrity.Verifier
	mux       *http.ServeMux
}

type deviceCredential struct {
	Type              string `json:"type"`
	CompanyID         string `json:"companyId"`
	DeviceID          string `json:"deviceId"`
	SignPublicKey     string `json:"signPublicKey"`
	WrapPublicKey     string `json:"wrapPublicKey"`
	PackageName       string `json:"packageName"`
	CertificateSHA256 string `json:"certificateSha256"`
	IssuedAt          int64  `json:"issuedAt"`
	ExpiresAt         int64  `json:"expiresAt"`
}

type payloadGrant struct {
	Type                    string `json:"type"`
	GrantID                 string `json:"grantId"`
	CompanyID               string `json:"companyId"`
	DeviceID                string `json:"deviceId"`
	DeviceWrapKeySHA256     string `json:"deviceWrapKeySha256"`
	ReleaseID               string `json:"releaseId"`
	PayloadID               string `json:"payloadId"`
	PayloadVersion          int64  `json:"payloadVersion"`
	PackageName             string `json:"packageName"`
	VersionCode             int64  `json:"versionCode"`
	CertificateSHA256       string `json:"certificateSha256"`
	CertificateSetSHA256    string `json:"certificateSetSha256"`
	ReleaseBuildSHA256      string `json:"releaseBuildSha256"`
	PayloadPlaintextSHA256  string `json:"payloadPlaintextSha256"`
	LocalCiphertextSHA256   string `json:"localCiphertextSha256"`
	PayloadKeyVersion       int64  `json:"payloadKeyVersion"`
	WrappedPayloadKeySHA256 string `json:"wrappedPayloadKeySha256"`
	WrapLabel               string `json:"wrapLabel"`
	IssuedAt                int64  `json:"issuedAt"`
	ExpiresAt               int64  `json:"expiresAt"`
}

func New(cfg config.Config, dbs *store.Manager) http.Handler {
	api := &API{cfg: cfg, dbs: dbs, mux: http.NewServeMux()}
	if cfg.IntegrityMode == "google" {
		verifier, err := integrity.NewGoogleVerifier(context.Background())
		if err != nil {
			panic(fmt.Sprintf("initialize Google Play Integrity verifier: %v", err))
		}
		api.integrity = verifier
	} else {
		api.integrity = integrity.DisabledVerifier{}
	}
	api.routes()
	return requestMiddlewareWithConfig(cfg.Logging, recoveryMiddleware(api.mux))
}

func (a *API) routes() {
	a.mux.HandleFunc("GET /healthz", a.health)
	a.mux.HandleFunc("GET /api/v1/companies", a.listCompanies)
	a.mux.HandleFunc("POST /api/v1/companies", a.createCompany)
	a.mux.HandleFunc("GET /api/v1/companies/{companyId}", a.getCompany)
	a.mux.HandleFunc("PATCH /api/v1/companies/{companyId}", a.updateCompany)
	a.mux.HandleFunc("DELETE /api/v1/companies/{companyId}", a.deleteCompany)
	a.mux.HandleFunc("GET /api/v1/companies/{companyId}/pack-logs", a.listPackLogs)
	a.mux.HandleFunc("GET /api/v1/companies/{companyId}/public-config", a.publicConfig)
	a.routesAdmin()

	a.mux.HandleFunc("GET /api/v1/companies/{companyId}/pack/auth-check", a.checkCompanyAuth)
	a.mux.HandleFunc("GET /api/v1/companies/{companyId}/pack/releases", a.listReleases)
	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/pack/releases", a.createRelease)
	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/seal", a.sealRelease)
	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/publish", a.publishRelease)
	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/revoke", a.revokeRelease)

	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/unpack/challenges", a.createChallenge)
	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/unpack/enroll", a.enrollDevice)
	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/unpack/authorize", a.authorizePayload)

	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/admin/revocations", a.addRevocation)
	apiNotFound := func(w http.ResponseWriter, r *http.Request) {
		writeErrorDetails(w, http.StatusNotFound, "API_NOT_FOUND", "API endpoint not found.", map[string]any{
			"method": r.Method, "path": r.URL.Path,
		})
	}
	for _, method := range []string{"GET", "POST", "PATCH", "DELETE", "PUT", "OPTIONS"} {
		a.mux.HandleFunc(method+" /api/", apiNotFound)
	}
}

func (a *API) health(w http.ResponseWriter, _ *http.Request) {
	writeResponse(w, http.StatusOK, "HEALTHY", "Jiagu server is healthy.", map[string]any{"status": "UP", "port": 8761, "integrityMode": a.cfg.IntegrityMode})
}

func (a *API) checkCompanyAuth(w http.ResponseWriter, r *http.Request) {
	lease, ok := a.requireCompany(w, r)
	if !ok {
		return
	}
	defer lease.Release()
	writeResponse(w, http.StatusOK, "COMPANY_AUTHORIZED", "Company API key is valid.", map[string]any{
		"companyId": r.PathValue("companyId"),
	})
}

func (a *API) createCompany(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	var input struct {
		CompanyID       string         `json:"companyId"`
		Description     string         `json:"description"`
		AuthorizedFrom  int64          `json:"authorizedFrom"`
		AuthorizedUntil int64          `json:"authorizedUntil"`
		PackLimit       int64          `json:"packLimit"`
		DeliveryLimit   int64          `json:"deliveryLimit"`
		Ext             map[string]any `json:"ext"`
	}
	if !decodeJSON(w, r, &input, 1<<20) {
		return
	}
	if input.AuthorizedFrom == 0 {
		input.AuthorizedFrom = time.Now().Unix()
	}
	if input.AuthorizedUntil != 0 && input.AuthorizedUntil <= input.AuthorizedFrom {
		writeError(w, http.StatusBadRequest, "INVALID_AUTHORIZATION_TIME", "authorizedUntil must be greater than authorizedFrom")
		return
	}
	apiKey, err := secure.RandomToken(32)
	if err != nil {
		writeInternal(w, err)
		return
	}
	ext, _ := json.Marshal(input.Ext)
	if input.Ext == nil {
		ext = []byte("{}")
	}
	lease, err := a.dbs.CreateCompany(r.Context(), store.CreateCompanyInput{
		CompanyID: input.CompanyID, Description: input.Description, AuthorizedFrom: input.AuthorizedFrom,
		AuthorizedUntil: input.AuthorizedUntil, PackLimit: input.PackLimit, DeliveryLimit: input.DeliveryLimit,
		ExtJSON: string(ext), APIKeyHash: secure.SHA256URL([]byte(apiKey)),
	})
	if err != nil {
		writeStoreError(w, err)
		return
	}
	lease.Release()
	writeResponse(w, http.StatusCreated, "COMPANY_CREATED", "Company created.", map[string]any{
		"companyId": input.CompanyID, "companyApiKey": apiKey,
		"warning": "companyApiKey is returned only once; store it securely",
	})
}

func (a *API) listCompanies(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	page, pageSize := pageParameters(r, 20)
	ids, total, err := a.dbs.CompanyIDsPage(page, pageSize)
	if err != nil {
		writeInternal(w, err)
		return
	}
	companies := make([]store.Company, 0, len(ids))
	failures := make([]map[string]string, 0)
	for _, id := range ids {
		lease, err := a.dbs.Acquire(r.Context(), id)
		if err != nil {
			failures = append(failures, map[string]string{"companyId": id, "error": err.Error()})
			continue
		}
		company, err := store.GetCompany(r.Context(), lease.DB)
		lease.Release()
		if err == nil {
			companies = append(companies, company)
		} else {
			failures = append(failures, map[string]string{"companyId": id, "error": err.Error()})
		}
	}
	writeResponse(w, http.StatusOK, "COMPANIES_LISTED", "Companies listed.", map[string]any{
		"items": companies, "failures": failures, "total": total, "page": page, "pageSize": pageSize,
	})
}

func (a *API) listPackLogs(w http.ResponseWriter, r *http.Request) {
	lease, ok := a.openCompany(w, r)
	if !ok {
		return
	}
	defer lease.Release()
	db := lease.DB
	// companyID := r.PathValue("companyId")
	// Dual authentication: Admin or Company API Key
	isAdmin := r.Header.Get("Authorization") == "Bearer "+a.cfg.AdminToken
	isAuthorized := false
	if isAdmin {
		isAuthorized = true
	} else {
		key := r.Header.Get("X-Company-Key")
		if key != "" {
			if err := store.AuthenticateCompany(r.Context(), db, secure.SHA256URL([]byte(key))); err == nil {
				isAuthorized = true
			}
		}
	}

	if !isAuthorized {
		writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", "invalid admin token or company API key")
		return
	}

	page, _ := strconv.Atoi(r.URL.Query().Get("page"))
	pageSize, _ := strconv.Atoi(r.URL.Query().Get("pageSize"))
	if page < 1 {
		page = 1
	}
	if pageSize < 1 {
		pageSize = 5 // Changed from 20 to 5 for testing
	}
	logs, total, err := store.ListPackLogs(r.Context(), db, page, pageSize)
	if err != nil {
		writeInternal(w, err)
		return
	}
	writeResponse(w, http.StatusOK, "PACK_LOGS_LISTED", "Pack logs listed.", map[string]any{
		"items": logs, "total": total, "page": page, "pageSize": pageSize,
	})
}

func (a *API) getCompany(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	lease, ok := a.openCompany(w, r)
	if !ok {
		return
	}
	defer lease.Release()
	db := lease.DB
	company, err := store.GetCompany(r.Context(), db)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeResponse(w, http.StatusOK, "COMPANY_FOUND", "Company found.", company)
}

func (a *API) updateCompany(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	lease, ok := a.openCompany(w, r)
	if !ok {
		return
	}
	defer lease.Release()
	db := lease.DB
	current, err := store.GetCompany(r.Context(), db)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	var input struct {
		Description     *string         `json:"description"`
		AuthorizedFrom  *int64          `json:"authorizedFrom"`
		AuthorizedUntil *int64          `json:"authorizedUntil"`
		PackLimit       *int64          `json:"packLimit"`
		DeliveryLimit   *int64          `json:"deliveryLimit"`
		Status          *string         `json:"status"`
		Ext             *map[string]any `json:"ext"`
	}
	if !decodeJSON(w, r, &input, 1<<20) {
		return
	}
	description, from, until := current.Description, current.AuthorizedFrom, current.AuthorizedUntil
	packLimit, deliveryLimit, status, extJSON := current.PackLimit, current.DeliveryLimit, current.Status, current.ExtJSON
	if input.Description != nil {
		description = *input.Description
	}
	if input.AuthorizedFrom != nil {
		from = *input.AuthorizedFrom
	}
	if input.AuthorizedUntil != nil {
		until = *input.AuthorizedUntil
	}
	if input.PackLimit != nil {
		packLimit = *input.PackLimit
	}
	if input.DeliveryLimit != nil {
		deliveryLimit = *input.DeliveryLimit
	}
	if input.Status != nil {
		status = strings.ToUpper(*input.Status)
	}
	if input.Ext != nil {
		value, _ := json.Marshal(*input.Ext)
		extJSON = string(value)
	}
	if current.Status == "REVOKED" && status != "REVOKED" {
		writeError(w, http.StatusConflict, "COMPANY_REVOKED", "a revoked company cannot be restored")
		return
	}
	if packLimit < 0 || deliveryLimit < 0 || (until != 0 && until <= from) || !validCompanyStatus(status) {
		writeError(w, http.StatusBadRequest, "INVALID_COMPANY", "invalid limits or authorization time")
		return
	}
	if err := store.UpdateCompany(r.Context(), db, description, from, until, packLimit, deliveryLimit, status, extJSON); err != nil {
		writeStoreError(w, err)
		return
	}
	updated, _ := store.GetCompany(r.Context(), db)
	writeResponse(w, http.StatusOK, "COMPANY_UPDATED", "Company updated.", updated)
}

// deleteCompany performs a logical delete. The per-company database and its
// audit history remain available to administrators, while all authorization
// checks reject the company because its status is REVOKED.
func (a *API) deleteCompany(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	lease, ok := a.openCompany(w, r)
	if !ok {
		return
	}
	defer lease.Release()
	db := lease.DB
	current, err := store.GetCompany(r.Context(), db)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if current.Status != "REVOKED" {
		if err := store.UpdateCompany(r.Context(), db, current.Description, current.AuthorizedFrom,
			current.AuthorizedUntil, current.PackLimit, current.DeliveryLimit, "REVOKED", current.ExtJSON); err != nil {
			writeStoreError(w, err)
			return
		}
		store.AddOperationLog(r.Context(), db, "COMPANY_REVOKE", requestID(r.Context()), current.CompanyID, "")
	}
	writeResponse(w, http.StatusOK, "COMPANY_REVOKED", "Company revoked.", map[string]any{"companyId": current.CompanyID})
}

func (a *API) publicConfig(w http.ResponseWriter, r *http.Request) {
	companyID := r.PathValue("companyId")
	lease, err := a.dbs.Acquire(r.Context(), companyID)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	lease.Release()
	privateKey := secure.CompanySigningKey(a.cfg.MasterKey, companyID)
	writeResponse(w, http.StatusOK, "PUBLIC_CONFIG_FOUND", "Public configuration found.", map[string]any{
		"companyId": companyID, "grantAlgorithm": "EdDSA", "serverKeyId": "company-sign-v1",
		"serverPublicKey":             secure.PublicKeyURL(privateKey.Public().(ed25519.PublicKey)),
		"integrityMode":               a.cfg.IntegrityMode,
		"integrityCloudProjectNumber": a.cfg.IntegrityCloudProjectNumber,
	})
}

func (a *API) createRelease(w http.ResponseWriter, r *http.Request) {
	lease, ok := a.requireCompany(w, r)
	if !ok {
		return
	}
	defer lease.Release()
	db := lease.DB
	company, err := store.GetCompany(r.Context(), db)
	if err != nil || !companyActive(company) {
		writeError(w, http.StatusForbidden, "COMPANY_NOT_AUTHORIZED", "company authorization is inactive")
		return
	}
	var input struct {
		PayloadID                string   `json:"payloadId"`
		PayloadVersion           int64    `json:"payloadVersion"`
		PackageName              string   `json:"packageName"`
		VersionCode              int64    `json:"versionCode"`
		Packer                   string   `json:"packer"`
		CertificateSHA256Digests []string `json:"certificateSha256Digests"`
		BusinessDexSHA256        string   `json:"businessDexSha256"`
		ResourcesSHA256          string   `json:"resourcesSha256"`
		NativeLibsSHA256         string   `json:"nativeLibsSha256"`
		PayloadPlaintextSHA256   string   `json:"payloadPlaintextSha256"`
	}
	if !decodeJSON(w, r, &input, 1<<20) {
		return
	}
	input.PayloadID = strings.TrimSpace(input.PayloadID)
	input.PackageName = strings.TrimSpace(input.PackageName)
	input.Packer = truncateRunes(strings.TrimSpace(input.Packer), 64)
	certificates, certificateJSON, certificateSetHash, certErr := normalizeCertificateDigests(input.CertificateSHA256Digests)
	if input.VersionCode <= 0 || input.PayloadVersion != input.VersionCode || input.PayloadID != "app-main" ||
		input.PackageName == "" || certErr != nil {
		message := "payloadId=app-main, payloadVersion=versionCode, packageName, versionCode and certificateSha256Digests are required"
		if certErr != nil {
			message = certErr.Error()
		}
		writeError(w, http.StatusBadRequest, "INVALID_RELEASE", message)
		return
	}
	businessHash := strings.TrimSpace(input.BusinessDexSHA256)
	resourcesHash := strings.TrimSpace(input.ResourcesSHA256)
	nativeHash := strings.TrimSpace(input.NativeLibsSHA256)
	plaintextHash := strings.TrimSpace(input.PayloadPlaintextSHA256)
	for name, value := range map[string]string{
		"businessDexSha256": businessHash, "resourcesSha256": resourcesHash,
		"nativeLibsSha256": nativeHash, "payloadPlaintextSha256": plaintextHash,
	} {
		if !validSHA256URL(value) {
			writeError(w, http.StatusBadRequest, "INVALID_DIGEST", name+" must be a 32-byte SHA-256 Base64URL value")
			return
		}
	}
	releaseBuildHash := secure.SHA256URL(canonical("JIAGU-RELEASE-BUILD-V1", businessHash, resourcesHash, nativeHash))
	requested := store.Release{
		PayloadID: input.PayloadID, PayloadVersion: input.PayloadVersion, PackageName: input.PackageName, VersionCode: input.VersionCode,
		CertificateSHA256Digests: certificates, CertificateDigestsJSON: certificateJSON,
		CertificateSetSHA256: certificateSetHash, BusinessDexSHA256: businessHash,
		ResourcesSHA256: resourcesHash, NativeLibsSHA256: nativeHash, ReleaseBuildSHA256: releaseBuildHash,
		PlaintextSHA256: plaintextHash, Packer: input.Packer,
	}
	companyID := r.PathValue("companyId")
	for attempt := 0; attempt < 4; attempt++ {
		existing, getErr := store.GetReleaseByVersion(r.Context(), db, input.PackageName, input.VersionCode)
		if errors.Is(getErr, store.ErrNotFound) {
			requested.ReleaseID, err = secure.RandomToken(18)
			requested.PayloadKeyVersion = 1
			key, keyErr := secure.RandomBytes(32)
			if keyErr != nil {
				writeInternal(w, keyErr)
				return
			}
			if err = a.protectPayloadKey(companyID, &requested, key); err != nil {
				clear(key)
				writeInternal(w, err)
				return
			}
			if err = store.CreateRelease(r.Context(), db, store.NewRelease{Release: requested}); errors.Is(err, store.ErrConflict) {
				clear(key)
				continue
			} else if err != nil {
				clear(key)
				writeStoreError(w, err)
				return
			}
			now := time.Now().Unix()
			requested.Status, requested.CreatedAt, requested.UpdatedAt = "DRAFT", now, now
			store.AddOperationLog(r.Context(), db, "PACK_CREATE", requestID(r.Context()), requested.ReleaseID, requested.Packer)
			a.writePreparedRelease(w, http.StatusCreated, "RELEASE_CREATED", "Draft release prepared.", requested, "CREATED", false, key)
			clear(key)
			return
		}
		if getErr != nil {
			writeStoreError(w, getErr)
			return
		}
		if existing.Status == "REVOKED" {
			writeErrorDetails(w, http.StatusConflict, "REVOKED_VERSION_REUSE_FORBIDDEN",
				"A revoked application version cannot be reused. Increase versionCode.", map[string]any{
					"packageName": input.PackageName, "versionCode": input.VersionCode,
				})
			return
		}
		changed := changedReleaseComponents(existing, requested)
		if len(changed) == 0 {
			// A published release is immutable. Packer metadata may only follow the
			// latest build host while the release is still a draft.
			if existing.Status == "DRAFT" && requested.Packer != "" && requested.Packer != existing.Packer {
				if err := store.UpdateReleasePacker(r.Context(), db, existing.ReleaseID, requested.Packer); err != nil {
					writeStoreError(w, err)
					return
				}
				existing.Packer = requested.Packer
				existing.UpdatedAt = time.Now().Unix()
			}
			key, unwrapErr := a.unprotectPayloadKey(companyID, existing)
			if unwrapErr != nil {
				writeInternal(w, unwrapErr)
				return
			}
			a.writePreparedRelease(w, http.StatusOK, "RELEASE_REUSED", "Existing release prepared.", existing, "REUSED", false, key)
			clear(key)
			return
		}
		switch existing.Status {
		case "PUBLISHED":
			writeErrorDetails(w, http.StatusConflict, "PUBLISHED_VERSION_MODIFIED",
				"Published application version cannot be modified. Increase versionCode.", map[string]any{
					"packageName": input.PackageName, "versionCode": input.VersionCode, "changedComponents": changed,
				})
			return
		case "DRAFT":
			updated := requested
			updated.ReleaseID = existing.ReleaseID
			updated.PayloadKeyVersion = existing.PayloadKeyVersion + 1
			updated.CreatedAt = existing.CreatedAt
			key, keyErr := secure.RandomBytes(32)
			if keyErr != nil {
				writeInternal(w, keyErr)
				return
			}
			if err = a.protectPayloadKey(companyID, &updated, key); err != nil {
				clear(key)
				writeInternal(w, err)
				return
			}
			if err = store.UpdateRelease(r.Context(), db, store.NewRelease{Release: updated}, existing.PayloadKeyVersion); errors.Is(err, store.ErrConflict) {
				clear(key)
				continue
			} else if err != nil {
				clear(key)
				writeStoreError(w, err)
				return
			}
			updated.Status, updated.UpdatedAt = "DRAFT", time.Now().Unix()
			a.writePreparedRelease(w, http.StatusOK, "RELEASE_UPDATED", "Draft release prepared.", updated, "UPDATED", true, key)
			clear(key)
			return
		default:
			writeError(w, http.StatusConflict, "INVALID_RELEASE_STATUS", "release has an unsupported status")
			return
		}
	}
	writeError(w, http.StatusConflict, "CONCURRENT_RELEASE_UPDATE", "release changed concurrently; retry the build")
}

func (a *API) sealRelease(w http.ResponseWriter, r *http.Request) {
	lease, ok := a.requireCompany(w, r)
	if !ok {
		return
	}
	defer lease.Release()
	db := lease.DB
	var input struct {
		LocalCiphertextSHA256 string `json:"localCiphertextSha256"`
		LocalPayloadSize      int64  `json:"localPayloadSize"`
	}
	if !decodeJSON(w, r, &input, 1<<20) {
		return
	}
	if !validSHA256URL(input.LocalCiphertextSHA256) || input.LocalPayloadSize <= 40 ||
		input.LocalPayloadSize > a.cfg.MaxPayloadBytes+64 {
		writeError(w, http.StatusBadRequest, "INVALID_LOCAL_PAYLOAD", "local payload digest or size is invalid")
		return
	}
	releaseID := r.PathValue("releaseId")
	if err := store.SealLocalPayload(r.Context(), db, releaseID, input.LocalCiphertextSHA256, input.LocalPayloadSize); err != nil {
		writeStoreError(w, err)
		return
	}
	release, err := store.GetReleaseMetadata(r.Context(), db, releaseID)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeResponse(w, http.StatusOK, "LOCAL_PAYLOAD_SEALED", "Local payload metadata sealed.", releaseDetails(release, "SEALED", false))
}

func (a *API) listReleases(w http.ResponseWriter, r *http.Request) {
	lease, ok := a.requireCompany(w, r)
	if !ok {
		return
	}
	defer lease.Release()
	db := lease.DB
	pageSize := queryInt(r, "pageSize", 20)
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	beforeCreatedAt, _ := strconv.ParseInt(r.URL.Query().Get("beforeCreatedAt"), 10, 64)
	beforeReleaseID := r.URL.Query().Get("beforeReleaseId")
	if (beforeCreatedAt > 0) != (beforeReleaseID != "") {
		writeError(w, http.StatusBadRequest, "INVALID_CURSOR", "beforeCreatedAt and beforeReleaseId must be provided together")
		return
	}
	values, hasMore, err := store.ListReleasesPage(r.Context(), db, beforeCreatedAt, beforeReleaseID, pageSize)
	if err != nil {
		writeInternal(w, err)
		return
	}
	items := make([]map[string]any, 0, len(values))
	for _, value := range values {
		items = append(items, releaseDetails(value, "", false))
	}
	details := map[string]any{"items": items, "pageSize": pageSize, "hasMore": hasMore}
	if hasMore && len(values) > 0 {
		last := values[len(values)-1]
		details["nextBeforeCreatedAt"] = last.CreatedAt
		details["nextBeforeReleaseId"] = last.ReleaseID
	}
	writeResponse(w, http.StatusOK, "RELEASES_LISTED", "Releases listed.", details)
}

func (a *API) publishRelease(w http.ResponseWriter, r *http.Request) {
	a.changeReleaseStatus(w, r, "PUBLISHED", "PACK_PUBLISH")
}
func (a *API) revokeRelease(w http.ResponseWriter, r *http.Request) {
	a.changeReleaseStatus(w, r, "REVOKED", "PACK_REVOKE")
}

func (a *API) changeReleaseStatus(w http.ResponseWriter, r *http.Request, status, operation string) {
	lease, ok := a.requireCompany(w, r)
	if !ok {
		return
	}
	defer lease.Release()
	db := lease.DB
	packer, changed, err := store.SetReleaseStatus(r.Context(), db, r.PathValue("releaseId"), status)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if changed {
		store.AddOperationLog(r.Context(), db, operation, requestID(r.Context()), r.PathValue("releaseId"), packer)
	}
	code, message := "RELEASE_REVOKED", "Release revoked."
	if status == "PUBLISHED" {
		code, message = "RELEASE_PUBLISHED", "Release published."
		if !changed {
			message = "Release is already published; no changes were made."
		}
	}
	writeResponse(w, http.StatusOK, code, message, map[string]any{
		"releaseId": r.PathValue("releaseId"), "status": status, "changed": changed,
	})
}

func (a *API) createChallenge(w http.ResponseWriter, r *http.Request) {
	lease, ok := a.openCompany(w, r)
	if !ok {
		return
	}
	defer lease.Release()
	db := lease.DB
	company, err := store.GetCompany(r.Context(), db)
	if err != nil || !companyActive(company) {
		writeError(w, http.StatusForbidden, "COMPANY_NOT_AUTHORIZED", "company authorization is inactive")
		return
	}
	var input struct {
		Purpose string `json:"purpose"`
	}
	if !decodeJSON(w, r, &input, 64<<10) {
		return
	}
	input.Purpose = strings.ToUpper(input.Purpose)
	if input.Purpose != "ENROLL" && input.Purpose != "AUTHORIZE" {
		writeError(w, http.StatusBadRequest, "INVALID_PURPOSE", "purpose must be ENROLL or AUTHORIZE")
		return
	}
	id, _ := secure.RandomToken(18)
	challenge, _ := secure.RandomToken(32)
	expires := time.Now().Add(a.cfg.ChallengeTTL).Unix()
	if err := store.CreateChallenge(r.Context(), db, id, challenge, input.Purpose, expires); err != nil {
		writeInternal(w, err)
		return
	}
	writeResponse(w, http.StatusCreated, "CHALLENGE_CREATED", "Challenge created.", map[string]any{"challengeId": id, "challenge": challenge, "purpose": input.Purpose, "expiresAt": expires})
}

func (a *API) enrollDevice(w http.ResponseWriter, r *http.Request) {
	lease, ok := a.openCompany(w, r)
	if !ok {
		return
	}
	defer lease.Release()
	db := lease.DB
	company, err := store.GetCompany(r.Context(), db)
	if err != nil || !companyActive(company) {
		writeError(w, http.StatusForbidden, "COMPANY_NOT_AUTHORIZED", "company authorization is inactive")
		return
	}
	var input struct {
		ChallengeID             string `json:"challengeId"`
		Challenge               string `json:"challenge"`
		ReleaseID               string `json:"releaseId"`
		ActualCertificateSHA256 string `json:"actualCertificateSha256"`
		SignPublicKey           string `json:"signPublicKey"`
		WrapPublicKey           string `json:"wrapPublicKey"`
		IntegrityToken          string `json:"integrityToken"`
		DeviceSignature         string `json:"deviceSignature"`
	}
	if !decodeJSON(w, r, &input, 2<<20) {
		return
	}
	release, ok := publishedRelease(w, r, db, input.ReleaseID)
	if !ok {
		return
	}
	signKey, signDER, err := secure.ParseECDSAPublicKey(input.SignPublicKey)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_SIGN_KEY", err.Error())
		return
	}
	_, wrapDER, err := secure.ParseRSAPublicKey(input.WrapPublicKey)
	if err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_WRAP_KEY", err.Error())
		return
	}
	deviceID := secure.SHA256URL(append(append([]byte{}, signDER...), wrapDER...))
	if !containsString(release.CertificateSHA256Digests, input.ActualCertificateSHA256) {
		writeErrorDetails(w, http.StatusForbidden, "APP_IDENTITY_MISMATCH", "installed signing certificate is not allowed for this release", map[string]any{
			"expectedCertificateSetSha256": release.CertificateSetSHA256,
		})
		return
	}
	message := canonical("ENROLL-V2", r.PathValue("companyId"), input.ChallengeID, input.Challenge,
		input.ReleaseID, release.PackageName, strconv.FormatInt(release.VersionCode, 10), input.ActualCertificateSHA256,
		release.CertificateSetSHA256, release.ReleaseBuildSHA256,
		input.SignPublicKey, input.WrapPublicKey)
	if err := secure.VerifyECDSA(signKey, message, input.DeviceSignature); err != nil {
		writeError(w, http.StatusUnauthorized, "INVALID_DEVICE_PROOF", err.Error())
		return
	}
	if err := a.integrity.Verify(r.Context(), input.IntegrityToken, integrity.Expected{
		RequestHash: secure.SHA256URL(message), PackageName: release.PackageName,
		VersionCode: release.VersionCode, CertificateSHA256: input.ActualCertificateSHA256,
	}); err != nil {
		writeError(w, http.StatusForbidden, "INTEGRITY_REJECTED", err.Error())
		return
	}
	if err := store.ConsumeChallenge(r.Context(), db, input.ChallengeID, input.Challenge, "ENROLL"); err != nil {
		writeStoreError(w, err)
		return
	}
	now := time.Now()
	credential := deviceCredential{
		Type: "DEVICE_CREDENTIAL", CompanyID: r.PathValue("companyId"), DeviceID: deviceID,
		SignPublicKey: input.SignPublicKey, WrapPublicKey: input.WrapPublicKey,
		PackageName: release.PackageName, CertificateSHA256: input.ActualCertificateSHA256,
		IssuedAt: now.Unix(), ExpiresAt: now.Add(a.cfg.DeviceCredentialTTL).Unix(),
	}
	token, err := secure.SignJWS(secure.CompanySigningKey(a.cfg.MasterKey, credential.CompanyID), "company-sign-v1", credential)
	if err != nil {
		writeInternal(w, err)
		return
	}
	writeResponse(w, http.StatusCreated, "DEVICE_ENROLLED", "Device enrolled.", map[string]any{"deviceId": deviceID, "deviceCredential": token, "expiresAt": credential.ExpiresAt})
}

func (a *API) authorizePayload(w http.ResponseWriter, r *http.Request) {
	lease, ok := a.openCompany(w, r)
	if !ok {
		return
	}
	defer lease.Release()
	db := lease.DB
	var input struct {
		ChallengeID      string `json:"challengeId"`
		Challenge        string `json:"challenge"`
		ReleaseID        string `json:"releaseId"`
		DeviceCredential string `json:"deviceCredential"`
		IntegrityToken   string `json:"integrityToken"`
		DeviceSignature  string `json:"deviceSignature"`
	}
	if !decodeJSON(w, r, &input, 2<<20) {
		return
	}
	companyID := r.PathValue("companyId")
	company, err := store.GetCompany(r.Context(), db)
	if err != nil || !companyActive(company) {
		writeError(w, http.StatusForbidden, "COMPANY_NOT_AUTHORIZED", "company authorization is inactive")
		return
	}
	var credential deviceCredential
	publicKey := secure.CompanySigningKey(a.cfg.MasterKey, companyID).Public().(ed25519.PublicKey)
	if err := secure.VerifyJWS(publicKey, input.DeviceCredential, &credential); err != nil || credential.Type != "DEVICE_CREDENTIAL" ||
		credential.CompanyID != companyID || credential.ExpiresAt < time.Now().Unix() {
		writeError(w, http.StatusUnauthorized, "INVALID_DEVICE_CREDENTIAL", "device credential is invalid or expired")
		return
	}
	revoked, err := store.IsRevoked(r.Context(), db, "DEVICE", credential.DeviceID)
	if err != nil {
		writeInternal(w, err)
		return
	}
	if revoked {
		writeError(w, http.StatusForbidden, "DEVICE_REVOKED", "device is revoked")
		return
	}
	release, ok := publishedRelease(w, r, db, input.ReleaseID)
	if !ok {
		return
	}
	if release.PackageName != credential.PackageName || !containsString(release.CertificateSHA256Digests, credential.CertificateSHA256) {
		writeErrorDetails(w, http.StatusForbidden, "APP_IDENTITY_MISMATCH", "release does not match device credential", map[string]any{
			"expectedVersionCode": release.VersionCode, "expectedPayloadKeyVersion": release.PayloadKeyVersion,
			"expectedReleaseBuildSha256": release.ReleaseBuildSHA256,
		})
		return
	}
	signKey, _, err := secure.ParseECDSAPublicKey(credential.SignPublicKey)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "INVALID_DEVICE_CREDENTIAL", err.Error())
		return
	}
	message := canonical("AUTHORIZE-V2", companyID, input.ChallengeID, input.Challenge, input.ReleaseID,
		secure.SHA256URL([]byte(input.DeviceCredential)), credential.DeviceID,
		release.ReleaseBuildSHA256, strconv.FormatInt(release.PayloadKeyVersion, 10))
	if err := secure.VerifyECDSA(signKey, message, input.DeviceSignature); err != nil {
		writeError(w, http.StatusUnauthorized, "INVALID_DEVICE_PROOF", err.Error())
		return
	}
	if err := a.integrity.Verify(r.Context(), input.IntegrityToken, integrity.Expected{
		RequestHash: secure.SHA256URL(message), PackageName: release.PackageName,
		VersionCode: release.VersionCode, CertificateSHA256: credential.CertificateSHA256,
	}); err != nil {
		writeError(w, http.StatusForbidden, "INTEGRITY_REJECTED", err.Error())
		return
	}
	if err := store.ConsumeChallenge(r.Context(), db, input.ChallengeID, input.Challenge, "AUTHORIZE"); err != nil {
		writeStoreError(w, err)
		return
	}
	wrapKey, wrapDER, err := secure.ParseRSAPublicKey(credential.WrapPublicKey)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "INVALID_WRAP_KEY", err.Error())
		return
	}
	payloadKey, err := a.unprotectPayloadKey(companyID, release)
	if err != nil {
		writeInternal(w, errors.New("cannot unwrap local payload key"))
		return
	}
	grantID, _ := secure.RandomToken(18)
	// Android KeyStore RSA-OAEP often does not support non-empty labels (P parameter).
	// We use an empty label to ensure compatibility.
	wrapped, err := secure.WrapRSAOAEP(wrapKey, payloadKey, nil)
	clear(payloadKey)
	if err != nil {
		writeInternal(w, err)
		return
	}
	now := time.Now()
	grant := payloadGrant{
		Type: "PAYLOAD_GRANT", GrantID: grantID, CompanyID: companyID, DeviceID: credential.DeviceID,
		DeviceWrapKeySHA256: secure.SHA256URL(wrapDER), ReleaseID: release.ReleaseID,
		PayloadID: release.PayloadID, PayloadVersion: release.PayloadVersion, PackageName: release.PackageName,
		VersionCode: release.VersionCode, CertificateSHA256: credential.CertificateSHA256,
		CertificateSetSHA256: release.CertificateSetSHA256, ReleaseBuildSHA256: release.ReleaseBuildSHA256,
		PayloadPlaintextSHA256: release.PlaintextSHA256, LocalCiphertextSHA256: release.LocalCiphertextSHA256,
		PayloadKeyVersion:       release.PayloadKeyVersion,
		WrappedPayloadKeySHA256: secure.SHA256URL([]byte(wrapped)), WrapLabel: "",
		IssuedAt: now.Unix(), ExpiresAt: now.Add(a.cfg.GrantTTL).Unix(),
	}
	grantToken, err := secure.SignJWS(secure.CompanySigningKey(a.cfg.MasterKey, companyID), "company-sign-v1", grant)
	if err != nil {
		writeInternal(w, err)
		return
	}
	if err := store.IncrementDelivery(r.Context(), db, release.ReleaseID); err != nil {
		writeStoreError(w, err)
		return
	}
	writeResponse(w, http.StatusOK, "PAYLOAD_AUTHORIZED", "Payload access authorized.", map[string]any{
		"grant": grantToken, "wrappedPayloadKey": wrapped, "wrapAlgorithm": "RSA-OAEP-SHA1",
		"wrapLabel": "", "expiresAt": grant.ExpiresAt,
	})
}

func (a *API) addRevocation(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	lease, ok := a.openCompany(w, r)
	if !ok {
		return
	}
	defer lease.Release()
	db := lease.DB
	var input struct {
		TargetType string `json:"targetType"`
		TargetHash string `json:"targetHash"`
		Reason     string `json:"reason"`
		ExpiresAt  int64  `json:"expiresAt"`
	}
	if !decodeJSON(w, r, &input, 1<<20) {
		return
	}
	input.TargetType = strings.ToUpper(input.TargetType)
	if input.TargetHash == "" || input.Reason == "" {
		writeError(w, http.StatusBadRequest, "INVALID_REVOCATION", "targetHash and reason are required")
		return
	}
	id, _ := secure.RandomToken(18)
	if err := store.AddRevocation(r.Context(), db, id, input.TargetType, input.TargetHash, input.Reason, input.ExpiresAt); err != nil {
		writeInternal(w, err)
		return
	}
	writeResponse(w, http.StatusCreated, "REVOCATION_CREATED", "Revocation created.", map[string]any{"revocationId": id})
}

func (a *API) requireAdmin(w http.ResponseWriter, r *http.Request) bool {
	if r.Header.Get("Authorization") != "Bearer "+a.cfg.AdminToken {
		writeError(w, http.StatusUnauthorized, "ADMIN_UNAUTHORIZED", "invalid admin token")
		return false
	}
	return true
}

func (a *API) requireCompany(w http.ResponseWriter, r *http.Request) (*store.Lease, bool) {
	lease, ok := a.openCompany(w, r)
	if !ok {
		return nil, false
	}
	if err := store.AuthenticateCompany(r.Context(), lease.DB, secure.SHA256URL([]byte(r.Header.Get("X-Company-Key")))); err != nil {
		lease.Release()
		writeError(w, http.StatusUnauthorized, "COMPANY_UNAUTHORIZED", "invalid company API key")
		return nil, false
	}
	return lease, true
}

func publishedRelease(w http.ResponseWriter, r *http.Request, db *sql.DB, releaseID string) (store.Release, bool) {
	release, err := store.GetRelease(r.Context(), db, releaseID, false)
	return availableRelease(w, release, err)
}

func publishedReleaseMetadata(w http.ResponseWriter, r *http.Request, db *sql.DB, releaseID string) (store.Release, bool) {
	release, err := store.GetReleaseMetadata(r.Context(), db, releaseID)
	return availableRelease(w, release, err)
}

func availableRelease(w http.ResponseWriter, release store.Release, err error) (store.Release, bool) {
	if err != nil {
		writeStoreError(w, err)
		return store.Release{}, false
	}
	// Allow DRAFT releases to be authorized for rapid debug iteration without
	// versionCode increments. REVOKED releases are never available.
	if release.Status != "PUBLISHED" && release.Status != "DRAFT" {
		writeErrorDetails(w, http.StatusGone, "RELEASE_NOT_AVAILABLE",
			"release is no longer available", map[string]any{
				"releaseId": release.ReleaseID,
				"status":    release.Status,
			})
		return store.Release{}, false
	}
	if release.LocalCiphertextSHA256 == "" || release.LocalPayloadSize <= 0 {
		writeError(w, http.StatusConflict, "LOCAL_PAYLOAD_NOT_SEALED", "release has no sealed local payload metadata")
		return store.Release{}, false
	}
	return release, true
}

func (a *API) openCompany(w http.ResponseWriter, r *http.Request) (*store.Lease, bool) {
	lease, err := a.dbs.Acquire(r.Context(), r.PathValue("companyId"))
	if err != nil {
		writeStoreError(w, err)
		return nil, false
	}
	return lease, true
}

func companyActive(company store.Company) bool {
	now := time.Now().Unix()
	return company.Status == "ACTIVE" && now >= company.AuthorizedFrom && (company.AuthorizedUntil == 0 || now <= company.AuthorizedUntil)
}

func validCompanyStatus(status string) bool {
	return status == "ACTIVE" || status == "SUSPENDED" || status == "EXPIRED" || status == "REVOKED"
}

func normalizeCertificateDigests(values []string) ([]string, string, string, error) {
	seen := make(map[string]struct{})
	for _, value := range values {
		value = strings.TrimSpace(value)
		if !validSHA256URL(value) {
			return nil, "", "", errors.New("certificateSha256Digest must contain one or more 32-byte SHA-256 Base64URL values")
		}
		seen[value] = struct{}{}
	}
	if len(seen) == 0 {
		return nil, "", "", errors.New("at least one certificateSha256Digest is required")
	}
	digests := make([]string, 0, len(seen))
	for value := range seen {
		digests = append(digests, value)
	}
	sort.Strings(digests)
	encoded, err := json.Marshal(digests)
	if err != nil {
		return nil, "", "", err
	}
	canonicalValues := append([]string{"JIAGU-CERTIFICATE-SET-V1"}, digests...)
	return digests, string(encoded), secure.SHA256URL(canonical(canonicalValues...)), nil
}

func validSHA256URL(value string) bool {
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	return err == nil && len(decoded) == 32 && base64.RawURLEncoding.EncodeToString(decoded) == value
}

func (a *API) protectPayloadKey(companyID string, release *store.Release, key []byte) error {
	companyKEK := secure.DeriveCompanyKey(a.cfg.MasterKey, companyID, "payload-key-wrap-v3")
	wrapped, err := secure.EncryptAESGCM(companyKEK, key, canonical("PAYLOAD-KEY-V3", companyID, release.ReleaseID))
	if err != nil {
		return err
	}
	release.PayloadKeyCiphertext = wrapped
	return nil
}

func (a *API) unprotectPayloadKey(companyID string, release store.Release) ([]byte, error) {
	companyKEK := secure.DeriveCompanyKey(a.cfg.MasterKey, companyID, "payload-key-wrap-v3")
	key, err := secure.DecryptAESGCM(companyKEK, release.PayloadKeyCiphertext,
		canonical("PAYLOAD-KEY-V3", companyID, release.ReleaseID))
	if err != nil || len(key) != 32 {
		clear(key)
		return nil, errors.New("payload key unwrap failed")
	}
	return key, nil
}

func (a *API) writePreparedRelease(w http.ResponseWriter, status int, code, message string,
	release store.Release, operation string, keyRotated bool, key []byte) {
	details := releaseDetails(release, operation, keyRotated)
	details["payloadKey"] = base64.StdEncoding.EncodeToString(key)
	writeResponse(w, status, code, message, details)
}

func changedReleaseComponents(existing, requested store.Release) []string {
	var changed []string
	if existing.BusinessDexSHA256 != requested.BusinessDexSHA256 {
		changed = append(changed, "BUSINESS_DEX")
	}
	if existing.ResourcesSHA256 != requested.ResourcesSHA256 {
		changed = append(changed, "RESOURCES")
	}
	if existing.NativeLibsSHA256 != requested.NativeLibsSHA256 {
		changed = append(changed, "NATIVE_LIBS")
	}
	if existing.CertificateSetSHA256 != requested.CertificateSetSHA256 {
		changed = append(changed, "SIGNING_CERTIFICATES")
	}
	if existing.PayloadID != requested.PayloadID || existing.PayloadVersion != requested.PayloadVersion ||
		existing.PlaintextSHA256 != requested.PlaintextSHA256 {
		changed = append(changed, "PAYLOAD")
	}
	return changed
}

func truncateRunes(value string, max int) string {
	values := []rune(value)
	if len(values) <= max {
		return value
	}
	return string(values[:max])
}

func releaseDetails(release store.Release, operation string, keyRotated bool) map[string]any {
	return map[string]any{
		"releaseId": release.ReleaseID, "payloadId": release.PayloadID, "payloadVersion": release.PayloadVersion,
		"packageName": release.PackageName, "versionCode": release.VersionCode,
		"packer":                   release.Packer,
		"certificateSha256Digests": release.CertificateSHA256Digests,
		"certificateSetSha256":     release.CertificateSetSHA256, "businessDexSha256": release.BusinessDexSHA256,
		"resourcesSha256": release.ResourcesSHA256, "nativeLibsSha256": release.NativeLibsSHA256,
		"releaseBuildSha256": release.ReleaseBuildSHA256, "payloadPlaintextSha256": release.PlaintextSHA256,
		"localCiphertextSha256": release.LocalCiphertextSHA256, "localPayloadSize": release.LocalPayloadSize,
		"payloadKeyVersion": release.PayloadKeyVersion, "deliveryCount": release.DeliveryCount, "status": release.Status,
		"createdAt": release.CreatedAt, "updatedAt": release.UpdatedAt,
		"publishedAt": release.PublishedAt, "revokedAt": release.RevokedAt,
		"operation": operation, "keyRotated": keyRotated,
	}
}

func containsString(values []string, expected string) bool {
	for _, value := range values {
		if value == expected {
			return true
		}
	}
	return false
}

func grantMatchesRelease(grant payloadGrant, release store.Release) bool {
	return grant.ReleaseID == release.ReleaseID && grant.PayloadID == release.PayloadID &&
		grant.PayloadVersion == release.PayloadVersion && grant.PackageName == release.PackageName &&
		grant.VersionCode == release.VersionCode && containsString(release.CertificateSHA256Digests, grant.CertificateSHA256) &&
		grant.CertificateSetSHA256 == release.CertificateSetSHA256 && grant.ReleaseBuildSHA256 == release.ReleaseBuildSHA256 &&
		grant.PayloadPlaintextSHA256 == release.PlaintextSHA256 && grant.LocalCiphertextSHA256 == release.LocalCiphertextSHA256 &&
		grant.PayloadKeyVersion == release.PayloadKeyVersion
}

func canonical(values ...string) []byte {
	var builder strings.Builder
	for _, value := range values {
		builder.WriteString(strconv.Itoa(len(value)))
		builder.WriteByte(':')
		builder.WriteString(value)
		builder.WriteByte('\n')
	}
	return []byte(builder.String())
}

func queryInt(r *http.Request, name string, fallback int) int {
	value, err := strconv.Atoi(r.URL.Query().Get(name))
	if err != nil {
		return fallback
	}
	return value
}

func pageParameters(r *http.Request, defaultPageSize int) (int, int) {
	page := queryInt(r, "page", 1)
	pageSize := queryInt(r, "pageSize", defaultPageSize)
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = defaultPageSize
	}
	return page, pageSize
}

type contextKey string

const requestIDKey contextKey = "requestId"

func recoveryMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if recovered := recover(); recovered != nil {
				zap.L().Error("panic recovered",
					zap.String("requestId", requestID(r.Context())),
					zap.String("method", r.Method),
					zap.String("path", r.URL.Path),
					zap.Any("panic", recovered),
					zap.ByteString("stack", debug.Stack()))

				// Once headers or a response body have been sent, the status code can
				// no longer be replaced safely. The access log still records the
				// original response and the panic log contains the failure details.
				if tracked, ok := w.(*statusWriter); ok && tracked.committed {
					return
				}
				writeResponse(w, http.StatusInternalServerError, "INTERNAL_ERROR", "internal server error", map[string]any{})
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func requestMiddleware(next http.Handler) http.Handler {
	return requestMiddlewareWithConfig(config.LoggingConfig{
		SuccessSampleRate: 1, SlowRequestThreshold: 500 * time.Millisecond,
	}, next)
}

func requestMiddlewareWithConfig(logging config.LoggingConfig, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		startedAt := time.Now()
		id := r.Header.Get("X-Request-Id")
		if id == "" {
			id, _ = secure.RandomToken(12)
		}
		w.Header().Set("X-Request-Id", id)
		w.Header().Set("X-Content-Type-Options", "nosniff")
		ctx := context.WithValue(r.Context(), requestIDKey, id)
		tracked := &statusWriter{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(tracked, r.WithContext(ctx))
		duration := time.Since(startedAt)
		if shouldLogRequest(logging, id, r.URL.Path, tracked.status, duration) {
			zap.L().Info("http request", zap.String("requestId", id), zap.String("method", r.Method),
				zap.String("path", r.URL.Path), zap.Int("status", tracked.status), zap.Int("bytes", tracked.bytes),
				zap.Duration("duration", duration))
		}
	})
}

func shouldLogRequest(logging config.LoggingConfig, requestID, path string, status int, duration time.Duration) bool {
	if status >= http.StatusBadRequest {
		return true
	}
	slowThreshold := logging.SlowRequestThreshold
	if slowThreshold <= 0 {
		slowThreshold = 500 * time.Millisecond
	}
	if duration >= slowThreshold {
		return true
	}
	if path == "/healthz" || path == "/readyz" || logging.SuccessSampleRate <= 0 {
		return false
	}
	if logging.SuccessSampleRate >= 1 {
		return true
	}
	hash := fnv.New32a()
	_, _ = hash.Write([]byte(requestID))
	return float64(hash.Sum32())/float64(^uint32(0)) < logging.SuccessSampleRate
}

type statusWriter struct {
	http.ResponseWriter
	status    int
	bytes     int
	committed bool
}

func (w *statusWriter) WriteHeader(status int) {
	if w.committed {
		return
	}
	w.committed = true
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *statusWriter) Write(value []byte) (int, error) {
	if !w.committed {
		w.WriteHeader(http.StatusOK)
	}
	written, err := w.ResponseWriter.Write(value)
	w.bytes += written
	return written, err
}

func requestID(ctx context.Context) string {
	value, _ := ctx.Value(requestIDKey).(string)
	return value
}

func decodeJSON(w http.ResponseWriter, r *http.Request, target any, max int64) bool {
	r.Body = http.MaxBytesReader(w, r.Body, max)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_JSON", err.Error())
		return false
	}
	return true
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeResponse(w http.ResponseWriter, status int, code, message string, details any) {
	if details == nil {
		details = map[string]any{}
	}
	writeJSON(w, status, map[string]any{"code": code, "message": message, "details": details})
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeErrorDetails(w, status, code, message, map[string]any{})
}

func writeErrorDetails(w http.ResponseWriter, status int, code, message string, details map[string]any) {
	zap.L().Warn("sending error response", zap.Int("status", status), zap.String("code", code), zap.String("message", message))
	writeResponse(w, status, code, message, details)
}

func writeInternal(w http.ResponseWriter, err error) {
	zap.L().Error("request failed", zap.Error(err))
	writeError(w, http.StatusInternalServerError, "INTERNAL_ERROR", "internal server error")
}

func writeStoreError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "NOT_FOUND", err.Error())
	case errors.Is(err, store.ErrConflict):
		writeError(w, http.StatusConflict, "CONFLICT", err.Error())
	case errors.Is(err, store.ErrAlreadyRevoked):
		writeError(w, http.StatusConflict, "RELEASE_ALREADY_REVOKED", err.Error())
	case errors.Is(err, store.ErrInvalidTransition):
		writeError(w, http.StatusConflict, "INVALID_RELEASE_STATUS_TRANSITION", err.Error())
	case errors.Is(err, store.ErrUnauthorized):
		writeError(w, http.StatusUnauthorized, "COMPANY_UNAUTHORIZED", err.Error())
	case errors.Is(err, store.ErrLimitExceeded):
		writeError(w, http.StatusTooManyRequests, "PACK_LIMIT_EXCEEDED", err.Error())
	case errors.Is(err, store.ErrAuthorization):
		writeError(w, http.StatusForbidden, "COMPANY_NOT_AUTHORIZED", err.Error())
	case errors.Is(err, store.ErrChallengeUsed):
		writeError(w, http.StatusConflict, "CHALLENGE_REJECTED", err.Error())
	default:
		writeInternal(w, err)
	}
}
