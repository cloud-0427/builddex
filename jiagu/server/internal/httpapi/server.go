package httpapi

import (
	"context"
	"crypto/ed25519"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
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
	PayloadPlaintextSHA256  string `json:"payloadPlaintextSha256"`
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
	return requestMiddleware(api.mux)
}

func (a *API) routes() {
	a.mux.HandleFunc("GET /healthz", a.health)
	a.mux.HandleFunc("GET /api/v1/companies", a.listCompanies)
	a.mux.HandleFunc("POST /api/v1/companies", a.createCompany)
	a.mux.HandleFunc("GET /api/v1/companies/{companyId}", a.getCompany)
	a.mux.HandleFunc("PATCH /api/v1/companies/{companyId}", a.updateCompany)
	a.mux.HandleFunc("DELETE /api/v1/companies/{companyId}", a.deleteCompany)
	a.mux.HandleFunc("GET /api/v1/companies/{companyId}/public-config", a.publicConfig)
	a.routesAdmin()

	a.mux.HandleFunc("GET /api/v1/companies/{companyId}/pack/releases", a.listReleases)
	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/pack/releases", a.createRelease)
	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/publish", a.publishRelease)
	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/pack/releases/{releaseId}/revoke", a.revokeRelease)

	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/unpack/challenges", a.createChallenge)
	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/unpack/enroll", a.enrollDevice)
	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/unpack/authorize", a.authorizePayload)
	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/unpack/download", a.downloadPayload)

	a.mux.HandleFunc("POST /api/v1/companies/{companyId}/admin/revocations", a.addRevocation)
}

func (a *API) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"status": "UP", "port": 8761, "integrityMode": a.cfg.IntegrityMode})
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
	_, err = a.dbs.CreateCompany(r.Context(), store.CreateCompanyInput{
		CompanyID: input.CompanyID, Description: input.Description, AuthorizedFrom: input.AuthorizedFrom,
		AuthorizedUntil: input.AuthorizedUntil, PackLimit: input.PackLimit, DeliveryLimit: input.DeliveryLimit,
		ExtJSON: string(ext), APIKeyHash: secure.SHA256URL([]byte(apiKey)),
	})
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{
		"companyId": input.CompanyID, "companyApiKey": apiKey,
		"warning": "companyApiKey is returned only once; store it securely",
	})
}

func (a *API) listCompanies(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	ids, err := a.dbs.CompanyIDs()
	if err != nil {
		writeInternal(w, err)
		return
	}
	companies := make([]store.Company, 0, len(ids))
	for _, id := range ids {
		db, err := a.dbs.Open(id)
		if err != nil {
			continue
		}
		company, err := store.GetCompany(r.Context(), db)
		if err == nil {
			companies = append(companies, company)
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": companies})
}

func (a *API) getCompany(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	db, ok := a.openCompany(w, r)
	if !ok {
		return
	}
	company, err := store.GetCompany(r.Context(), db)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, company)
}

func (a *API) updateCompany(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	db, ok := a.openCompany(w, r)
	if !ok {
		return
	}
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
	writeJSON(w, http.StatusOK, updated)
}

// deleteCompany performs a logical delete. The per-company database and its
// audit history remain available to administrators, while all authorization
// checks reject the company because its status is REVOKED.
func (a *API) deleteCompany(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	db, ok := a.openCompany(w, r)
	if !ok {
		return
	}
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
		store.AddOperationLog(r.Context(), db, "COMPANY_REVOKE", "SUCCESS", requestID(r.Context()), current.CompanyID)
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *API) publicConfig(w http.ResponseWriter, r *http.Request) {
	companyID := r.PathValue("companyId")
	if _, err := a.dbs.Open(companyID); err != nil {
		writeStoreError(w, err)
		return
	}
	privateKey := secure.CompanySigningKey(a.cfg.MasterKey, companyID)
	writeJSON(w, http.StatusOK, map[string]any{
		"companyId": companyID, "grantAlgorithm": "EdDSA", "serverKeyId": "company-sign-v1",
		"serverPublicKey": secure.PublicKeyURL(privateKey.Public().(ed25519.PublicKey)),
		"integrityMode":   a.cfg.IntegrityMode,
	})
}

func (a *API) createRelease(w http.ResponseWriter, r *http.Request) {
	db, ok := a.requireCompany(w, r)
	if !ok {
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, a.cfg.MaxPayloadBytes+(2<<20))
	if err := r.ParseMultipartForm(2 << 20); err != nil {
		writeError(w, http.StatusBadRequest, "INVALID_MULTIPART", err.Error())
		return
	}
	file, _, err := r.FormFile("payload")
	if err != nil {
		writeError(w, http.StatusBadRequest, "PAYLOAD_REQUIRED", "multipart field payload is required")
		return
	}
	defer file.Close()
	plaintext, err := io.ReadAll(io.LimitReader(file, a.cfg.MaxPayloadBytes+1))
	if err != nil || int64(len(plaintext)) > a.cfg.MaxPayloadBytes {
		writeError(w, http.StatusRequestEntityTooLarge, "PAYLOAD_TOO_LARGE", "payload exceeds configured limit")
		return
	}
	versionCode, err1 := strconv.ParseInt(r.FormValue("versionCode"), 10, 64)
	payloadVersion, err2 := strconv.ParseInt(r.FormValue("payloadVersion"), 10, 64)
	if len(plaintext) == 0 || err1 != nil || err2 != nil || versionCode <= 0 || payloadVersion <= 0 ||
		r.FormValue("payloadId") == "" || r.FormValue("packageName") == "" || r.FormValue("certificateSha256") == "" {
		writeError(w, http.StatusBadRequest, "INVALID_RELEASE", "payloadId, payloadVersion, packageName, versionCode, certificateSha256 and payload are required")
		return
	}
	releaseID, _ := secure.RandomToken(18)
	key, err := secure.RandomBytes(32)
	if err != nil {
		writeInternal(w, err)
		return
	}
	release := store.Release{
		ReleaseID: releaseID, PayloadID: r.FormValue("payloadId"), PayloadVersion: payloadVersion,
		PackageName: r.FormValue("packageName"), VersionCode: versionCode,
		CertificateSHA256: r.FormValue("certificateSha256"), PlaintextSHA256: secure.SHA256URL(plaintext), PayloadKeyVersion: 1,
	}
	canonicalAAD := canonicalReleaseAAD(r.PathValue("companyId"), release)
	release.CanonicalPayload, err = secure.EncryptAESGCM(key, plaintext, canonicalAAD)
	if err != nil {
		writeInternal(w, err)
		return
	}
	release.CanonicalCipherSHA256 = secure.SHA256URL(release.CanonicalPayload)
	companyKEK := secure.DeriveCompanyKey(a.cfg.MasterKey, r.PathValue("companyId"), "canonical-key-wrap-v1")
	release.CanonicalKeyCiphertext, err = secure.EncryptAESGCM(companyKEK, key, canonical("CANONICAL-KEY-V1", r.PathValue("companyId"), releaseID))
	clear(key)
	clear(plaintext)
	if err != nil {
		writeInternal(w, err)
		return
	}
	if err := store.CreateRelease(r.Context(), db, store.NewRelease{Release: release}); err != nil {
		writeStoreError(w, err)
		return
	}
	store.AddOperationLog(r.Context(), db, "PACK_CREATE", "SUCCESS", requestID(r.Context()), releaseID)
	release.CanonicalPayload, release.CanonicalKeyCiphertext = nil, nil
	writeJSON(w, http.StatusCreated, release)
}

func (a *API) listReleases(w http.ResponseWriter, r *http.Request) {
	db, ok := a.requireCompany(w, r)
	if !ok {
		return
	}
	values, err := store.ListReleases(r.Context(), db)
	if err != nil {
		writeInternal(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": values})
}

func (a *API) publishRelease(w http.ResponseWriter, r *http.Request) {
	a.changeReleaseStatus(w, r, "PUBLISHED", "PACK_PUBLISH")
}
func (a *API) revokeRelease(w http.ResponseWriter, r *http.Request) {
	a.changeReleaseStatus(w, r, "REVOKED", "PACK_REVOKE")
}

func (a *API) changeReleaseStatus(w http.ResponseWriter, r *http.Request, status, operation string) {
	db, ok := a.requireCompany(w, r)
	if !ok {
		return
	}
	if err := store.SetReleaseStatus(r.Context(), db, r.PathValue("releaseId"), status); err != nil {
		writeStoreError(w, err)
		return
	}
	store.AddOperationLog(r.Context(), db, operation, "SUCCESS", requestID(r.Context()), r.PathValue("releaseId"))
	writeJSON(w, http.StatusOK, map[string]any{"releaseId": r.PathValue("releaseId"), "status": status})
}

func (a *API) createChallenge(w http.ResponseWriter, r *http.Request) {
	db, ok := a.openCompany(w, r)
	if !ok {
		return
	}
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
	writeJSON(w, http.StatusCreated, map[string]any{"challengeId": id, "challenge": challenge, "purpose": input.Purpose, "expiresAt": expires})
}

func (a *API) enrollDevice(w http.ResponseWriter, r *http.Request) {
	db, ok := a.openCompany(w, r)
	if !ok {
		return
	}
	company, err := store.GetCompany(r.Context(), db)
	if err != nil || !companyActive(company) {
		writeError(w, http.StatusForbidden, "COMPANY_NOT_AUTHORIZED", "company authorization is inactive")
		return
	}
	var input struct {
		ChallengeID     string `json:"challengeId"`
		Challenge       string `json:"challenge"`
		ReleaseID       string `json:"releaseId"`
		SignPublicKey   string `json:"signPublicKey"`
		WrapPublicKey   string `json:"wrapPublicKey"`
		IntegrityToken  string `json:"integrityToken"`
		DeviceSignature string `json:"deviceSignature"`
	}
	if !decodeJSON(w, r, &input, 2<<20) {
		return
	}
	release, err := store.GetRelease(r.Context(), db, input.ReleaseID, true)
	if err != nil {
		writeStoreError(w, err)
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
	message := canonical("ENROLL-V1", r.PathValue("companyId"), input.ChallengeID, input.Challenge,
		input.ReleaseID, release.PackageName, strconv.FormatInt(release.VersionCode, 10), release.CertificateSHA256,
		input.SignPublicKey, input.WrapPublicKey)
	if err := secure.VerifyECDSA(signKey, message, input.DeviceSignature); err != nil {
		writeError(w, http.StatusUnauthorized, "INVALID_DEVICE_PROOF", err.Error())
		return
	}
	if err := a.integrity.Verify(r.Context(), input.IntegrityToken, integrity.Expected{
		RequestHash: secure.SHA256URL(message), PackageName: release.PackageName,
		VersionCode: release.VersionCode, CertificateSHA256: release.CertificateSHA256,
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
		PackageName: release.PackageName, CertificateSHA256: release.CertificateSHA256,
		IssuedAt: now.Unix(), ExpiresAt: now.Add(a.cfg.DeviceCredentialTTL).Unix(),
	}
	token, err := secure.SignJWS(secure.CompanySigningKey(a.cfg.MasterKey, credential.CompanyID), "company-sign-v1", credential)
	if err != nil {
		writeInternal(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, map[string]any{"deviceId": deviceID, "deviceCredential": token, "expiresAt": credential.ExpiresAt})
}

func (a *API) authorizePayload(w http.ResponseWriter, r *http.Request) {
	db, ok := a.openCompany(w, r)
	if !ok {
		return
	}
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
	release, err := store.GetRelease(r.Context(), db, input.ReleaseID, true)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if release.PackageName != credential.PackageName || release.CertificateSHA256 != credential.CertificateSHA256 {
		writeError(w, http.StatusForbidden, "APP_IDENTITY_MISMATCH", "release does not match device credential")
		return
	}
	signKey, _, err := secure.ParseECDSAPublicKey(credential.SignPublicKey)
	if err != nil {
		writeError(w, http.StatusUnauthorized, "INVALID_DEVICE_CREDENTIAL", err.Error())
		return
	}
	message := canonical("AUTHORIZE-V1", companyID, input.ChallengeID, input.Challenge, input.ReleaseID,
		secure.SHA256URL([]byte(input.DeviceCredential)), credential.DeviceID)
	if err := secure.VerifyECDSA(signKey, message, input.DeviceSignature); err != nil {
		writeError(w, http.StatusUnauthorized, "INVALID_DEVICE_PROOF", err.Error())
		return
	}
	if err := a.integrity.Verify(r.Context(), input.IntegrityToken, integrity.Expected{
		RequestHash: secure.SHA256URL(message), PackageName: release.PackageName,
		VersionCode: release.VersionCode, CertificateSHA256: release.CertificateSHA256,
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
	deviceKey := deriveDeviceKey(a.cfg.MasterKey, companyID, credential.DeviceID, release)
	grantID, _ := secure.RandomToken(18)
	wrapped, err := secure.WrapRSAOAEP(wrapKey, deviceKey, []byte(grantID))
	clear(deviceKey)
	if err != nil {
		writeInternal(w, err)
		return
	}
	now := time.Now()
	grant := payloadGrant{
		Type: "PAYLOAD_GRANT", GrantID: grantID, CompanyID: companyID, DeviceID: credential.DeviceID,
		DeviceWrapKeySHA256: secure.SHA256URL(wrapDER), ReleaseID: release.ReleaseID,
		PayloadID: release.PayloadID, PayloadVersion: release.PayloadVersion, PackageName: release.PackageName,
		VersionCode: release.VersionCode, CertificateSHA256: release.CertificateSHA256,
		PayloadPlaintextSHA256: release.PlaintextSHA256, PayloadKeyVersion: release.PayloadKeyVersion,
		WrappedPayloadKeySHA256: secure.SHA256URL([]byte(wrapped)), WrapLabel: grantID,
		IssuedAt: now.Unix(), ExpiresAt: now.Add(a.cfg.GrantTTL).Unix(),
	}
	grantToken, err := secure.SignJWS(secure.CompanySigningKey(a.cfg.MasterKey, companyID), "company-sign-v1", grant)
	if err != nil {
		writeInternal(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"grant": grantToken, "wrappedPayloadKey": wrapped, "wrapAlgorithm": "RSA-OAEP-SHA256",
		"wrapLabel": grantID, "downloadPath": fmt.Sprintf("/api/v1/companies/%s/unpack/download", companyID),
		"expiresAt": grant.ExpiresAt,
	})
}

func (a *API) downloadPayload(w http.ResponseWriter, r *http.Request) {
	db, ok := a.openCompany(w, r)
	if !ok {
		return
	}
	var input struct {
		Grant string `json:"grant"`
	}
	if !decodeJSON(w, r, &input, 1<<20) {
		return
	}
	companyID := r.PathValue("companyId")
	var grant payloadGrant
	publicKey := secure.CompanySigningKey(a.cfg.MasterKey, companyID).Public().(ed25519.PublicKey)
	if err := secure.VerifyJWS(publicKey, input.Grant, &grant); err != nil || grant.Type != "PAYLOAD_GRANT" ||
		grant.CompanyID != companyID || grant.ExpiresAt < time.Now().Unix() {
		writeError(w, http.StatusUnauthorized, "INVALID_GRANT", "payload grant is invalid or expired")
		return
	}
	if revoked, err := store.IsRevoked(r.Context(), db, "DEVICE", grant.DeviceID); err != nil {
		writeInternal(w, err)
		return
	} else if revoked {
		writeError(w, http.StatusForbidden, "DEVICE_REVOKED", "device is revoked")
		return
	}
	release, err := store.GetRelease(r.Context(), db, grant.ReleaseID, true)
	if err != nil {
		writeStoreError(w, err)
		return
	}
	if !grantMatchesRelease(grant, release) {
		writeError(w, http.StatusForbidden, "GRANT_BINDING_MISMATCH", "grant does not match current release")
		return
	}
	companyKEK := secure.DeriveCompanyKey(a.cfg.MasterKey, companyID, "canonical-key-wrap-v1")
	canonicalKey, err := secure.DecryptAESGCM(companyKEK, release.CanonicalKeyCiphertext,
		canonical("CANONICAL-KEY-V1", companyID, release.ReleaseID))
	if err != nil {
		writeInternal(w, errors.New("cannot unwrap canonical payload key"))
		return
	}
	plaintext, err := secure.DecryptAESGCM(canonicalKey, release.CanonicalPayload, canonicalReleaseAAD(companyID, release))
	clear(canonicalKey)
	if err != nil || secure.SHA256URL(plaintext) != release.PlaintextSHA256 {
		clear(plaintext)
		writeInternal(w, errors.New("canonical payload verification failed"))
		return
	}
	deviceKey := deriveDeviceKey(a.cfg.MasterKey, companyID, grant.DeviceID, release)
	devicePayload, err := secure.EncryptAESGCM(deviceKey, plaintext, devicePayloadAAD(grant))
	clear(deviceKey)
	clear(plaintext)
	if err != nil {
		writeInternal(w, err)
		return
	}
	if err := store.IncrementDelivery(r.Context(), db); err != nil {
		clear(devicePayload)
		writeStoreError(w, err)
		return
	}
	store.AddOperationLog(r.Context(), db, "UNPACK_DELIVERY", "SUCCESS", requestID(r.Context()), grant.ReleaseID)
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", `attachment; filename="payload.jgpd"`)
	w.Header().Set("X-Jiagu-Grant-Id", grant.GrantID)
	w.Header().Set("X-Jiagu-Payload-SHA256", grant.PayloadPlaintextSHA256)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(deviceContainer(devicePayload))
	clear(devicePayload)
}

func (a *API) addRevocation(w http.ResponseWriter, r *http.Request) {
	if !a.requireAdmin(w, r) {
		return
	}
	db, ok := a.openCompany(w, r)
	if !ok {
		return
	}
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
	store.AddOperationLog(r.Context(), db, "REVOCATION_CREATE", "SUCCESS", requestID(r.Context()), id)
	writeJSON(w, http.StatusCreated, map[string]any{"revocationId": id})
}

func (a *API) requireAdmin(w http.ResponseWriter, r *http.Request) bool {
	if r.Header.Get("Authorization") != "Bearer "+a.cfg.AdminToken {
		writeError(w, http.StatusUnauthorized, "ADMIN_UNAUTHORIZED", "invalid admin token")
		return false
	}
	return true
}

func (a *API) requireCompany(w http.ResponseWriter, r *http.Request) (*sql.DB, bool) {
	db, ok := a.openCompany(w, r)
	if !ok {
		return nil, false
	}
	if err := store.AuthenticateCompany(r.Context(), db, secure.SHA256URL([]byte(r.Header.Get("X-Company-Key")))); err != nil {
		writeError(w, http.StatusUnauthorized, "COMPANY_UNAUTHORIZED", "invalid company API key")
		return nil, false
	}
	return db, true
}

func (a *API) openCompany(w http.ResponseWriter, r *http.Request) (*sql.DB, bool) {
	db, err := a.dbs.Open(r.PathValue("companyId"))
	if err != nil {
		writeStoreError(w, err)
		return nil, false
	}
	return db, true
}

func companyActive(company store.Company) bool {
	now := time.Now().Unix()
	return company.Status == "ACTIVE" && now >= company.AuthorizedFrom && (company.AuthorizedUntil == 0 || now <= company.AuthorizedUntil)
}

func validCompanyStatus(status string) bool {
	return status == "ACTIVE" || status == "SUSPENDED" || status == "EXPIRED" || status == "REVOKED"
}

func deriveDeviceKey(master []byte, companyID, deviceID string, release store.Release) []byte {
	return secure.DeriveDevicePayloadKey(master, companyID, deviceID, release.ReleaseID, release.PayloadID,
		strconv.FormatInt(release.PayloadVersion, 10), release.PackageName, strconv.FormatInt(release.VersionCode, 10),
		release.CertificateSHA256, release.PlaintextSHA256, strconv.FormatInt(release.PayloadKeyVersion, 10))
}

func canonicalReleaseAAD(companyID string, release store.Release) []byte {
	return canonical("CANONICAL-PAYLOAD-V1", companyID, release.ReleaseID, release.PayloadID,
		strconv.FormatInt(release.PayloadVersion, 10), release.PackageName, strconv.FormatInt(release.VersionCode, 10),
		release.CertificateSHA256, release.PlaintextSHA256)
}

func devicePayloadAAD(grant payloadGrant) []byte {
	return canonical("DEVICE-PAYLOAD-V1", grant.CompanyID, grant.DeviceID, grant.ReleaseID, grant.PayloadID,
		strconv.FormatInt(grant.PayloadVersion, 10), grant.PackageName, strconv.FormatInt(grant.VersionCode, 10),
		grant.CertificateSHA256, grant.PayloadPlaintextSHA256, strconv.FormatInt(grant.PayloadKeyVersion, 10))
}

func grantMatchesRelease(grant payloadGrant, release store.Release) bool {
	return grant.ReleaseID == release.ReleaseID && grant.PayloadID == release.PayloadID &&
		grant.PayloadVersion == release.PayloadVersion && grant.PackageName == release.PackageName &&
		grant.VersionCode == release.VersionCode && grant.CertificateSHA256 == release.CertificateSHA256 &&
		grant.PayloadPlaintextSHA256 == release.PlaintextSHA256 && grant.PayloadKeyVersion == release.PayloadKeyVersion
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

func deviceContainer(encrypted []byte) []byte {
	result := make([]byte, 12+len(encrypted))
	copy(result[:4], []byte("JGPD"))
	binary.BigEndian.PutUint32(result[4:8], 1)
	binary.BigEndian.PutUint32(result[8:12], uint32(len(encrypted)))
	copy(result[12:], encrypted)
	return result
}

type contextKey string

const requestIDKey contextKey = "requestId"

func requestMiddleware(next http.Handler) http.Handler {
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
		zap.L().Info("http request", zap.String("requestId", id), zap.String("method", r.Method),
			zap.String("path", r.URL.Path), zap.Int("status", tracked.status), zap.Int("bytes", tracked.bytes),
			zap.Duration("duration", time.Since(startedAt)))
	})
}

type statusWriter struct {
	http.ResponseWriter
	status int
	bytes  int
}

func (w *statusWriter) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *statusWriter) Write(value []byte) (int, error) {
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

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]any{"error": map[string]string{"code": code, "message": message}})
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
	case errors.Is(err, store.ErrUnauthorized):
		writeError(w, http.StatusUnauthorized, "UNAUTHORIZED", err.Error())
	case errors.Is(err, store.ErrLimitExceeded):
		writeError(w, http.StatusTooManyRequests, "LIMIT_EXCEEDED", err.Error())
	case errors.Is(err, store.ErrAuthorization):
		writeError(w, http.StatusForbidden, "COMPANY_NOT_AUTHORIZED", err.Error())
	case errors.Is(err, store.ErrChallengeUsed):
		writeError(w, http.StatusConflict, "CHALLENGE_REJECTED", err.Error())
	default:
		writeInternal(w, err)
	}
}
