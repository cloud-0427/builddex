package httpapi

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/xjc/jiagu/server/internal/config"
	"github.com/xjc/jiagu/server/internal/secure"
	"github.com/xjc/jiagu/server/internal/store"
)

func TestEndToEndPrepareSealEnrollAndAuthorizeLocalPayload(t *testing.T) {
	master := bytes.Repeat([]byte{0x42}, 32)
	dbs, err := store.NewManager(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer dbs.Close()
	handler := New(config.Config{
		ListenAddr: ":8761", DataDir: t.TempDir(), AdminToken: "admin", MasterKey: master,
		MaxPayloadBytes: 1 << 20, ChallengeTTL: time.Minute, GrantTTL: time.Minute,
		DeviceCredentialTTL: time.Hour, IntegrityMode: "disabled",
	}, dbs)

	companyResponse := doJSON(t, handler, http.MethodPost, "/api/v1/companies", "Bearer admin", "", map[string]any{
		"companyId": "acme", "description": "test", "authorizedUntil": time.Now().Add(time.Hour).Unix(),
	})
	if companyResponse.Code != http.StatusCreated {
		t.Fatalf("create company: %d %s", companyResponse.Code, companyResponse.Body.String())
	}
	var companyCreated struct {
		CompanyAPIKey string `json:"companyApiKey"`
	}
	decodeResponse(t, companyResponse, &companyCreated)

	certificate := secure.SHA256URL([]byte("cert-digest"))
	prepareBody := releaseRequest("com.example.app", 7, certificate, []byte("secret dex payload"), "resources-v1")
	recorder := doJSON(t, handler, http.MethodPost, "/api/v1/companies/acme/pack/releases", "", companyCreated.CompanyAPIKey, prepareBody)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("pack release: %d %s", recorder.Code, recorder.Body.String())
	}
	var prepared struct {
		store.Release
		PayloadKey string `json:"payloadKey"`
	}
	decodeResponse(t, recorder, &prepared)
	release := prepared.Release
	localCiphertext := []byte("APK-local AES-GCM payload container")
	localCiphertextHash := secure.SHA256URL(localCiphertext)
	seal := doJSON(t, handler, http.MethodPost,
		"/api/v1/companies/acme/pack/releases/"+release.ReleaseID+"/seal", "", companyCreated.CompanyAPIKey, map[string]any{
			"localCiphertextSha256": localCiphertextHash, "localPayloadSize": len(localCiphertext) + 40,
		})
	if seal.Code != http.StatusOK {
		t.Fatalf("seal local payload: %d %s", seal.Code, seal.Body.String())
	}
	release.LocalCiphertextSHA256 = localCiphertextHash
	release.LocalPayloadSize = int64(len(localCiphertext) + 40)

	publish := doJSON(t, handler, http.MethodPost,
		"/api/v1/companies/acme/pack/releases/"+release.ReleaseID+"/publish", "", companyCreated.CompanyAPIKey, map[string]any{})
	if publish.Code != http.StatusOK {
		t.Fatalf("publish: %d %s", publish.Code, publish.Body.String())
	}
	republish := doJSON(t, handler, http.MethodPost,
		"/api/v1/companies/acme/pack/releases/"+release.ReleaseID+"/publish", "", companyCreated.CompanyAPIKey, map[string]any{})
	if republish.Code != http.StatusConflict || !bytes.Contains(republish.Body.Bytes(), []byte("RELEASE_ALREADY_PUBLISHED")) {
		t.Fatalf("republish must fail with the published state: %d %s", republish.Code, republish.Body.String())
	}
	missingPublish := doJSON(t, handler, http.MethodPost,
		"/api/v1/companies/acme/pack/releases/missing/publish", "", companyCreated.CompanyAPIKey, map[string]any{})
	if missingPublish.Code != http.StatusNotFound {
		t.Fatalf("missing release publish must fail: %d %s", missingPublish.Code, missingPublish.Body.String())
	}

	signPrivate, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	signDER, _ := x509.MarshalPKIXPublicKey(&signPrivate.PublicKey)
	wrapPrivate, _ := rsa.GenerateKey(rand.Reader, 2048)
	wrapDER, _ := x509.MarshalPKIXPublicKey(&wrapPrivate.PublicKey)
	signEncoded := base64.RawURLEncoding.EncodeToString(signDER)
	wrapEncoded := base64.RawURLEncoding.EncodeToString(wrapDER)

	enrollChallenge := newChallenge(t, handler, "ENROLL")
	enrollMessage := canonical("ENROLL-V2", "acme", enrollChallenge.ID, enrollChallenge.Value,
		release.ReleaseID, release.PackageName, strconv.FormatInt(release.VersionCode, 10), certificate,
		release.CertificateSetSHA256, release.ReleaseBuildSHA256,
		signEncoded, wrapEncoded)
	enroll := doJSON(t, handler, http.MethodPost, "/api/v1/companies/acme/unpack/enroll", "", "", map[string]any{
		"challengeId": enrollChallenge.ID, "challenge": enrollChallenge.Value, "releaseId": release.ReleaseID,
		"actualCertificateSha256": certificate,
		"signPublicKey":           signEncoded, "wrapPublicKey": wrapEncoded, "deviceSignature": signMessage(t, signPrivate, enrollMessage),
	})
	if enroll.Code != http.StatusCreated {
		t.Fatalf("enroll: %d %s", enroll.Code, enroll.Body.String())
	}
	var enrolled struct {
		DeviceID         string `json:"deviceId"`
		DeviceCredential string `json:"deviceCredential"`
	}
	decodeResponse(t, enroll, &enrolled)

	// A published release is not bound to the first enrolled device. A second
	// phone gets its own credential and device identity under the same company.
	secondSignPrivate, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	secondSignDER, _ := x509.MarshalPKIXPublicKey(&secondSignPrivate.PublicKey)
	secondWrapPrivate, _ := rsa.GenerateKey(rand.Reader, 2048)
	secondWrapDER, _ := x509.MarshalPKIXPublicKey(&secondWrapPrivate.PublicKey)
	secondSignEncoded := base64.RawURLEncoding.EncodeToString(secondSignDER)
	secondWrapEncoded := base64.RawURLEncoding.EncodeToString(secondWrapDER)
	secondChallenge := newChallenge(t, handler, "ENROLL")
	secondMessage := canonical("ENROLL-V2", "acme", secondChallenge.ID, secondChallenge.Value,
		release.ReleaseID, release.PackageName, strconv.FormatInt(release.VersionCode, 10), certificate,
		release.CertificateSetSHA256, release.ReleaseBuildSHA256,
		secondSignEncoded, secondWrapEncoded)
	secondEnroll := doJSON(t, handler, http.MethodPost, "/api/v1/companies/acme/unpack/enroll", "", "", map[string]any{
		"challengeId": secondChallenge.ID, "challenge": secondChallenge.Value, "releaseId": release.ReleaseID,
		"actualCertificateSha256": certificate,
		"signPublicKey":           secondSignEncoded, "wrapPublicKey": secondWrapEncoded,
		"deviceSignature": signMessage(t, secondSignPrivate, secondMessage),
	})
	if secondEnroll.Code != http.StatusCreated {
		t.Fatalf("second device enroll: %d %s", secondEnroll.Code, secondEnroll.Body.String())
	}
	var secondEnrolled struct {
		DeviceID         string `json:"deviceId"`
		DeviceCredential string `json:"deviceCredential"`
	}
	decodeResponse(t, secondEnroll, &secondEnrolled)
	if secondEnrolled.DeviceID == enrolled.DeviceID || secondEnrolled.DeviceCredential == enrolled.DeviceCredential {
		t.Fatal("different devices must receive independent identities and credentials")
	}

	authChallenge := newChallenge(t, handler, "AUTHORIZE")
	authMessage := canonical("AUTHORIZE-V2", "acme", authChallenge.ID, authChallenge.Value, release.ReleaseID,
		secure.SHA256URL([]byte(enrolled.DeviceCredential)), enrolled.DeviceID,
		release.ReleaseBuildSHA256, strconv.FormatInt(release.PayloadKeyVersion, 10))
	authorized := doJSON(t, handler, http.MethodPost, "/api/v1/companies/acme/unpack/authorize", "", "", map[string]any{
		"challengeId": authChallenge.ID, "challenge": authChallenge.Value, "releaseId": release.ReleaseID,
		"deviceCredential": enrolled.DeviceCredential, "deviceSignature": signMessage(t, signPrivate, authMessage),
	})
	if authorized.Code != http.StatusOK {
		t.Fatalf("authorize: %d %s", authorized.Code, authorized.Body.String())
	}
	var authorization struct {
		Grant             string `json:"grant"`
		WrappedPayloadKey string `json:"wrappedPayloadKey"`
		WrapAlgorithm     string `json:"wrapAlgorithm"`
		WrapLabel         string `json:"wrapLabel"`
	}
	decodeResponse(t, authorized, &authorization)
	if authorization.WrapAlgorithm != "RSA-OAEP-SHA1" || authorization.WrapLabel != "" {
		t.Fatalf("unexpected wrap parameters: %q label=%q", authorization.WrapAlgorithm, authorization.WrapLabel)
	}

	wrapped, _ := base64.RawURLEncoding.DecodeString(authorization.WrappedPayloadKey)
	payloadKey, err := rsa.DecryptOAEP(sha1.New(), rand.Reader, wrapPrivate, wrapped, []byte(authorization.WrapLabel))
	if err != nil {
		t.Fatal(err)
	}
	expectedKey, err := base64.StdEncoding.DecodeString(prepared.PayloadKey)
	if err != nil || !bytes.Equal(payloadKey, expectedKey) {
		t.Fatal("authorize must return the prepared local payload key")
	}
	var grant payloadGrant
	public := secure.CompanySigningKey(master, "acme").Public().(ed25519.PublicKey)
	if err := secure.VerifyJWS(public, authorization.Grant, &grant); err != nil {
		t.Fatal(err)
	}
	if grant.LocalCiphertextSHA256 != localCiphertextHash {
		t.Fatal("grant is not bound to the APK-local payload")
	}
	download := doJSON(t, handler, http.MethodPost, "/api/v1/companies/acme/unpack/download", "", "", map[string]any{"grant": authorization.Grant})
	if download.Code != http.StatusNotFound {
		t.Fatalf("download endpoint must be removed: %d %s", download.Code, download.Body.String())
	}
}

func TestReleaseIdempotency(t *testing.T) {
	master := bytes.Repeat([]byte{0x42}, 32)
	dbs, err := store.NewManager(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer dbs.Close()
	handler := New(config.Config{
		AdminToken: "admin", MasterKey: master, MaxPayloadBytes: 1 << 20,
		IntegrityMode: "disabled",
	}, dbs)

	companyResponse := doJSON(t, handler, http.MethodPost, "/api/v1/companies", "Bearer admin", "", map[string]any{
		"companyId": "acme",
	})
	var companyCreated struct {
		CompanyAPIKey string `json:"companyApiKey"`
	}
	decodeResponse(t, companyResponse, &companyCreated)

	// 1. Create a DRAFT release
	createDraft := func(version string, content []byte) *httptest.ResponseRecorder {
		versionCode, _ := strconv.ParseInt(version, 10, 64)
		return doJSON(t, handler, http.MethodPost, "/api/v1/companies/acme/pack/releases", "", companyCreated.CompanyAPIKey,
			releaseRequest("com.example.app", versionCode, secure.SHA256URL([]byte("cert")), content,
				"resources-"+string(content)))
	}

	res1 := createDraft("1", []byte("v1 content"))
	if res1.Code != http.StatusCreated {
		t.Fatalf("first create: %d %s", res1.Code, res1.Body.String())
	}
	var release1 store.Release
	decodeResponse(t, res1, &release1)

	// A prepared release cannot authorize devices until its APK-local payload metadata is sealed.
	draftEnroll := doJSON(t, handler, http.MethodPost, "/api/v1/companies/acme/unpack/enroll", "", "", map[string]any{
		"releaseId": release1.ReleaseID,
	})
	if draftEnroll.Code != http.StatusConflict || !bytes.Contains(draftEnroll.Body.Bytes(), []byte("LOCAL_PAYLOAD_NOT_SEALED")) {
		t.Fatalf("unsealed draft must be rejected: %d %s", draftEnroll.Code, draftEnroll.Body.String())
	}

	// 2. Update the same DRAFT in place.
	res2 := createDraft("1", []byte("v1 updated content"))
	if res2.Code != http.StatusOK {
		t.Fatalf("update draft: %d %s", res2.Code, res2.Body.String())
	}
	var release2 store.Release
	decodeResponse(t, res2, &release2)
	if release2.ReleaseID != release1.ReleaseID || release2.PayloadKeyVersion != release1.PayloadKeyVersion+1 {
		t.Fatal("draft update must preserve releaseId and increment payloadKeyVersion")
	}
	seal := doJSON(t, handler, http.MethodPost, "/api/v1/companies/acme/pack/releases/"+release2.ReleaseID+"/seal",
		"", companyCreated.CompanyAPIKey, map[string]any{
			"localCiphertextSha256": secure.SHA256URL([]byte("v1 local payload")), "localPayloadSize": 128,
		})
	if seal.Code != http.StatusOK {
		t.Fatalf("seal draft: %d %s", seal.Code, seal.Body.String())
	}

	// 3. Publish and try same content (should return 200)
	doJSON(t, handler, http.MethodPost, "/api/v1/companies/acme/pack/releases/"+release2.ReleaseID+"/publish", "", companyCreated.CompanyAPIKey, map[string]any{})
	res3 := createDraft("1", []byte("v1 updated content"))
	if res3.Code != http.StatusOK {
		t.Fatalf("idempotent published: %d %s", res3.Code, res3.Body.String())
	}
	var release3 store.Release
	decodeResponse(t, res3, &release3)
	if release3.ReleaseID != release2.ReleaseID {
		t.Fatal("should return existing releaseId")
	}

	// 4. Try different content for published (should return 409)
	res4 := createDraft("1", []byte("v1 different content"))
	if res4.Code != http.StatusConflict {
		t.Fatalf("conflict published: %d %s", res4.Code, res4.Body.String())
	}
	var conflict struct {
		Code    string `json:"code"`
		Details struct {
			PackageName       string   `json:"packageName"`
			VersionCode       int64    `json:"versionCode"`
			ChangedComponents []string `json:"changedComponents"`
		} `json:"details"`
	}
	if err := json.Unmarshal(res4.Body.Bytes(), &conflict); err != nil {
		t.Fatal(err)
	}
	if conflict.Code != "PUBLISHED_VERSION_MODIFIED" || conflict.Details.PackageName != "com.example.app" ||
		conflict.Details.VersionCode != 1 || len(conflict.Details.ChangedComponents) == 0 {
		t.Fatalf("unexpected published conflict envelope: %s", res4.Body.String())
	}

	// 5. Revoked versions can never be reused, even with byte-identical content.
	revoked := doJSON(t, handler, http.MethodPost,
		"/api/v1/companies/acme/pack/releases/"+release2.ReleaseID+"/revoke", "", companyCreated.CompanyAPIKey, map[string]any{})
	if revoked.Code != http.StatusOK {
		t.Fatalf("revoke release: %d %s", revoked.Code, revoked.Body.String())
	}
	revokeAgain := doJSON(t, handler, http.MethodPost,
		"/api/v1/companies/acme/pack/releases/"+release2.ReleaseID+"/revoke", "", companyCreated.CompanyAPIKey, map[string]any{})
	if revokeAgain.Code != http.StatusConflict || !bytes.Contains(revokeAgain.Body.Bytes(), []byte("RELEASE_ALREADY_REVOKED")) {
		t.Fatalf("repeated revoke must fail with the revoked state: %d %s", revokeAgain.Code, revokeAgain.Body.String())
	}
	publishRevoked := doJSON(t, handler, http.MethodPost,
		"/api/v1/companies/acme/pack/releases/"+release2.ReleaseID+"/publish", "", companyCreated.CompanyAPIKey, map[string]any{})
	if publishRevoked.Code != http.StatusConflict || !bytes.Contains(publishRevoked.Body.Bytes(), []byte("INVALID_RELEASE_STATUS_TRANSITION")) {
		t.Fatalf("revoked release cannot be published: %d %s", publishRevoked.Code, publishRevoked.Body.String())
	}
	res5 := createDraft("1", []byte("v1 updated content"))
	if res5.Code != http.StatusConflict || !bytes.Contains(res5.Body.Bytes(), []byte("REVOKED_VERSION_REUSE_FORBIDDEN")) {
		t.Fatalf("reuse revoked release: %d %s", res5.Code, res5.Body.String())
	}
}

func TestMultiPackageSupport(t *testing.T) {
	master := bytes.Repeat([]byte{0x42}, 32)
	dbs, err := store.NewManager(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer dbs.Close()
	handler := New(config.Config{
		AdminToken: "admin", MasterKey: master, MaxPayloadBytes: 1 << 20,
		IntegrityMode: "disabled",
	}, dbs)

	companyResponse := doJSON(t, handler, http.MethodPost, "/api/v1/companies", "Bearer admin", "", map[string]any{
		"companyId": "acme",
	})
	var companyCreated struct {
		CompanyAPIKey string `json:"companyApiKey"`
	}
	decodeResponse(t, companyResponse, &companyCreated)

	createRelease := func(pkg, version string) int {
		versionCode, _ := strconv.ParseInt(version, 10, 64)
		recorder := doJSON(t, handler, http.MethodPost, "/api/v1/companies/acme/pack/releases", "", companyCreated.CompanyAPIKey,
			releaseRequest(pkg, versionCode, secure.SHA256URL([]byte("cert")), []byte("content"), "resources-v1"))
		return recorder.Code
	}

	if code := createRelease("com.pkg.a", "1"); code != http.StatusCreated {
		t.Fatalf("pkg a: %d", code)
	}
	// Previously this would fail due to UNIQUE(payload_id, payload_version)
	if code := createRelease("com.pkg.b", "1"); code != http.StatusCreated {
		t.Fatalf("pkg b: %d", code)
	}
}

func TestCompanyManagementAndLogicalDelete(t *testing.T) {
	dbs, err := store.NewManager(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer dbs.Close()
	handler := New(config.Config{
		AdminToken: "admin", MasterKey: bytes.Repeat([]byte{0x42}, 32),
		MaxPayloadBytes: 1 << 20, ChallengeTTL: time.Minute, GrantTTL: time.Minute,
		DeviceCredentialTTL: time.Hour, IntegrityMode: "disabled",
	}, dbs)

	created := doJSON(t, handler, http.MethodPost, "/api/v1/companies", "Bearer admin", "", map[string]any{
		"companyId": "logical-delete", "description": "keep the database",
	})
	if created.Code != http.StatusCreated {
		t.Fatalf("create company: %d %s", created.Code, created.Body.String())
	}
	paused := doJSON(t, handler, http.MethodPatch, "/api/v1/companies/logical-delete", "Bearer admin", "", map[string]any{
		"status": "SUSPENDED",
	})
	if paused.Code != http.StatusOK {
		t.Fatalf("pause company: %d %s", paused.Code, paused.Body.String())
	}
	resumed := doJSON(t, handler, http.MethodPatch, "/api/v1/companies/logical-delete", "Bearer admin", "", map[string]any{
		"status": "ACTIVE",
	})
	if resumed.Code != http.StatusOK {
		t.Fatalf("resume company: %d %s", resumed.Code, resumed.Body.String())
	}

	deleted := doJSON(t, handler, http.MethodDelete, "/api/v1/companies/logical-delete", "Bearer admin", "", nil)
	if deleted.Code != http.StatusOK {
		t.Fatalf("delete company: %d %s", deleted.Code, deleted.Body.String())
	}

	got := doJSON(t, handler, http.MethodGet, "/api/v1/companies/logical-delete", "Bearer admin", "", nil)
	if got.Code != http.StatusOK {
		t.Fatalf("get deleted company: %d %s", got.Code, got.Body.String())
	}
	var company store.Company
	decodeResponse(t, got, &company)
	if company.Status != "REVOKED" {
		t.Fatalf("status = %q, want REVOKED", company.Status)
	}
	restore := doJSON(t, handler, http.MethodPatch, "/api/v1/companies/logical-delete", "Bearer admin", "", map[string]any{
		"status": "ACTIVE",
	})
	if restore.Code != http.StatusConflict {
		t.Fatalf("restore revoked company: %d %s", restore.Code, restore.Body.String())
	}

	again := doJSON(t, handler, http.MethodDelete, "/api/v1/companies/logical-delete", "Bearer admin", "", nil)
	if again.Code != http.StatusOK {
		t.Fatalf("idempotent delete: %d %s", again.Code, again.Body.String())
	}
}

func TestAdminPageIsServed(t *testing.T) {
	dbs, err := store.NewManager(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer dbs.Close()
	handler := New(config.Config{IntegrityMode: "disabled"}, dbs)

	req := httptest.NewRequest(http.MethodGet, "/admin/", nil)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK || !bytes.Contains(recorder.Body.Bytes(), []byte("公司管理")) {
		t.Fatalf("admin page: %d %s", recorder.Code, recorder.Body.String())
	}

	scriptRequest := httptest.NewRequest(http.MethodGet, "/admin/app.js", nil)
	scriptRecorder := httptest.NewRecorder()
	handler.ServeHTTP(scriptRecorder, scriptRequest)
	if scriptRecorder.Code != http.StatusOK {
		t.Fatalf("admin script: %d %s", scriptRecorder.Code, scriptRecorder.Body.String())
	}
	if scriptRecorder.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("admin script cache control: %q", scriptRecorder.Header().Get("Cache-Control"))
	}
	if !bytes.Contains(scriptRecorder.Body.Bytes(), []byte(`document.execCommand("copy")`)) {
		t.Fatal("admin script does not contain the clipboard compatibility path")
	}
}

func TestCompanyAuthCheckReturnsEnvelopeForValidAndInvalidKeys(t *testing.T) {
	dbs, err := store.NewManager(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer dbs.Close()
	handler := New(config.Config{
		AdminToken: "admin", MasterKey: bytes.Repeat([]byte{0x42}, 32),
	}, dbs)
	created := doJSON(t, handler, http.MethodPost, "/api/v1/companies", "Bearer admin", "", map[string]any{
		"companyId": "auth-check", "authorizedUntil": time.Now().Add(time.Hour).Unix(),
	})
	if created.Code != http.StatusCreated {
		t.Fatalf("create company: %d %s", created.Code, created.Body.String())
	}
	var company struct {
		CompanyAPIKey string `json:"companyApiKey"`
	}
	decodeResponse(t, created, &company)

	valid := doJSON(t, handler, http.MethodGet,
		"/api/v1/companies/auth-check/pack/auth-check", "", company.CompanyAPIKey, nil)
	if valid.Code != http.StatusOK {
		t.Fatalf("valid auth check: %d %s", valid.Code, valid.Body.String())
	}
	var validEnvelope struct {
		Code    string `json:"code"`
		Details struct {
			CompanyID string `json:"companyId"`
		} `json:"details"`
	}
	if err := json.Unmarshal(valid.Body.Bytes(), &validEnvelope); err != nil {
		t.Fatal(err)
	}
	if validEnvelope.Code != "COMPANY_AUTHORIZED" || validEnvelope.Details.CompanyID != "auth-check" {
		t.Fatalf("valid auth check response: %+v", validEnvelope)
	}

	invalid := doJSON(t, handler, http.MethodGet,
		"/api/v1/companies/auth-check/pack/auth-check", "", "wrong-key", nil)
	if invalid.Code != http.StatusUnauthorized {
		t.Fatalf("invalid auth check: %d %s", invalid.Code, invalid.Body.String())
	}
	var invalidEnvelope struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	}
	if err := json.Unmarshal(invalid.Body.Bytes(), &invalidEnvelope); err != nil {
		t.Fatal(err)
	}
	if invalidEnvelope.Code != "COMPANY_UNAUTHORIZED" || invalidEnvelope.Message == "" {
		t.Fatalf("invalid auth check response: %+v", invalidEnvelope)
	}
}

func TestUnknownAPIUsesResponseEnvelope(t *testing.T) {
	dbs, err := store.NewManager(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer dbs.Close()
	handler := New(config.Config{IntegrityMode: "disabled"}, dbs)
	response := doJSON(t, handler, http.MethodGet, "/api/v1/not-a-real-endpoint", "", "", nil)
	if response.Code != http.StatusNotFound || response.Header().Get("Content-Type") != "application/json; charset=utf-8" {
		t.Fatalf("unknown API response: %d %s", response.Code, response.Body.String())
	}
	var envelope struct {
		Code    string         `json:"code"`
		Message string         `json:"message"`
		Details map[string]any `json:"details"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.Code != "API_NOT_FOUND" || envelope.Message == "" || envelope.Details == nil {
		t.Fatalf("invalid error envelope: %s", response.Body.String())
	}
}

type challengeValue struct{ ID, Value string }

func newChallenge(t *testing.T, handler http.Handler, purpose string) challengeValue {
	t.Helper()
	response := doJSON(t, handler, http.MethodPost, "/api/v1/companies/acme/unpack/challenges", "", "", map[string]string{"purpose": purpose})
	if response.Code != http.StatusCreated {
		t.Fatalf("challenge: %d %s", response.Code, response.Body.String())
	}
	var value struct {
		ID        string `json:"challengeId"`
		Challenge string `json:"challenge"`
	}
	decodeResponse(t, response, &value)
	return challengeValue{ID: value.ID, Value: value.Challenge}
}

func signMessage(t *testing.T, key *ecdsa.PrivateKey, message []byte) string {
	t.Helper()
	digest := sha256.Sum256(message)
	signature, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	return base64.RawURLEncoding.EncodeToString(signature)
}

func doJSON(t *testing.T, handler http.Handler, method, path, admin, companyKey string, value any) *httptest.ResponseRecorder {
	t.Helper()
	body, _ := json.Marshal(value)
	req := httptest.NewRequest(method, path, bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	if admin != "" {
		req.Header.Set("Authorization", admin)
	}
	if companyKey != "" {
		req.Header.Set("X-Company-Key", companyKey)
	}
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, req)
	return recorder
}

func decodeResponse(t *testing.T, response *httptest.ResponseRecorder, target any) {
	t.Helper()
	var envelope struct {
		Details json.RawMessage `json:"details"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	if len(envelope.Details) == 0 {
		t.Fatalf("response is missing details: %s", response.Body.String())
	}
	if err := json.Unmarshal(envelope.Details, target); err != nil {
		t.Fatal(err)
	}
}

func releaseRequest(packageName string, versionCode int64, certificate string,
	payload []byte, resourcesSeed string) map[string]any {
	return map[string]any{
		"payloadId": "app-main", "payloadVersion": versionCode,
		"packageName": packageName, "versionCode": versionCode,
		"certificateSha256Digests": []string{certificate},
		"businessDexSha256":        secure.SHA256URL(append([]byte("business:"), payload...)),
		"resourcesSha256":          secure.SHA256URL([]byte(resourcesSeed)),
		"nativeLibsSha256":         secure.SHA256URL([]byte("native-v1")),
		"payloadPlaintextSha256":   secure.SHA256URL(payload),
	}
}
