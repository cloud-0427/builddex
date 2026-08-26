package httpapi

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/xjc/jiagu/server/internal/config"
	"github.com/xjc/jiagu/server/internal/secure"
	"github.com/xjc/jiagu/server/internal/store"
)

func TestEndToEndPackEnrollAuthorizeAndDownload(t *testing.T) {
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

	payload := []byte("secret dex payload")
	var multipartBody bytes.Buffer
	writer := multipart.NewWriter(&multipartBody)
	_ = writer.WriteField("payloadId", "core")
	_ = writer.WriteField("payloadVersion", "1")
	_ = writer.WriteField("packageName", "com.example.app")
	_ = writer.WriteField("versionCode", "7")
	_ = writer.WriteField("certificateSha256", "cert-digest")
	file, _ := writer.CreateFormFile("payload", "payload.bin")
	_, _ = file.Write(payload)
	_ = writer.Close()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/companies/acme/pack/releases", &multipartBody)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("X-Company-Key", companyCreated.CompanyAPIKey)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusCreated {
		t.Fatalf("pack release: %d %s", recorder.Code, recorder.Body.String())
	}
	var release store.Release
	decodeResponse(t, recorder, &release)

	publish := doJSON(t, handler, http.MethodPost,
		"/api/v1/companies/acme/pack/releases/"+release.ReleaseID+"/publish", "", companyCreated.CompanyAPIKey, map[string]any{})
	if publish.Code != http.StatusOK {
		t.Fatalf("publish: %d %s", publish.Code, publish.Body.String())
	}

	signPrivate, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	signDER, _ := x509.MarshalPKIXPublicKey(&signPrivate.PublicKey)
	wrapPrivate, _ := rsa.GenerateKey(rand.Reader, 2048)
	wrapDER, _ := x509.MarshalPKIXPublicKey(&wrapPrivate.PublicKey)
	signEncoded := base64.RawURLEncoding.EncodeToString(signDER)
	wrapEncoded := base64.RawURLEncoding.EncodeToString(wrapDER)

	enrollChallenge := newChallenge(t, handler, "ENROLL")
	enrollMessage := canonical("ENROLL-V1", "acme", enrollChallenge.ID, enrollChallenge.Value,
		release.ReleaseID, release.PackageName, strconv.FormatInt(release.VersionCode, 10), release.CertificateSHA256,
		signEncoded, wrapEncoded)
	enroll := doJSON(t, handler, http.MethodPost, "/api/v1/companies/acme/unpack/enroll", "", "", map[string]any{
		"challengeId": enrollChallenge.ID, "challenge": enrollChallenge.Value, "releaseId": release.ReleaseID,
		"signPublicKey": signEncoded, "wrapPublicKey": wrapEncoded, "deviceSignature": signMessage(t, signPrivate, enrollMessage),
	})
	if enroll.Code != http.StatusCreated {
		t.Fatalf("enroll: %d %s", enroll.Code, enroll.Body.String())
	}
	var enrolled struct {
		DeviceID         string `json:"deviceId"`
		DeviceCredential string `json:"deviceCredential"`
	}
	decodeResponse(t, enroll, &enrolled)

	authChallenge := newChallenge(t, handler, "AUTHORIZE")
	authMessage := canonical("AUTHORIZE-V1", "acme", authChallenge.ID, authChallenge.Value, release.ReleaseID,
		secure.SHA256URL([]byte(enrolled.DeviceCredential)), enrolled.DeviceID)
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
		WrapLabel         string `json:"wrapLabel"`
	}
	decodeResponse(t, authorized, &authorization)

	download := doJSON(t, handler, http.MethodPost, "/api/v1/companies/acme/unpack/download", "", "", map[string]any{"grant": authorization.Grant})
	if download.Code != http.StatusOK {
		t.Fatalf("download: %d %s", download.Code, download.Body.String())
	}
	container := download.Body.Bytes()
	if len(container) < 12 || string(container[:4]) != "JGPD" || binary.BigEndian.Uint32(container[4:8]) != 1 {
		t.Fatal("invalid device payload container")
	}
	wrapped, _ := base64.RawURLEncoding.DecodeString(authorization.WrappedPayloadKey)
	deviceKey, err := rsa.DecryptOAEP(sha256.New(), rand.Reader, wrapPrivate, wrapped, []byte(authorization.WrapLabel))
	if err != nil {
		t.Fatal(err)
	}
	var grant payloadGrant
	public := secure.CompanySigningKey(master, "acme").Public().(ed25519.PublicKey)
	if err := secure.VerifyJWS(public, authorization.Grant, &grant); err != nil {
		t.Fatal(err)
	}
	decrypted, err := secure.DecryptAESGCM(deviceKey, container[12:], devicePayloadAAD(grant))
	if err != nil || !bytes.Equal(decrypted, payload) {
		t.Fatalf("device payload decrypt failed: %v", err)
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
		var multipartBody bytes.Buffer
		writer := multipart.NewWriter(&multipartBody)
		_ = writer.WriteField("payloadId", "core")
		_ = writer.WriteField("payloadVersion", version)
		_ = writer.WriteField("packageName", "com.example.app")
		_ = writer.WriteField("versionCode", version)
		_ = writer.WriteField("certificateSha256", "cert")
		file, _ := writer.CreateFormFile("payload", "payload.bin")
		_, _ = file.Write(content)
		_ = writer.Close()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/companies/acme/pack/releases", &multipartBody)
		req.Header.Set("Content-Type", writer.FormDataContentType())
		req.Header.Set("X-Company-Key", companyCreated.CompanyAPIKey)
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, req)
		return recorder
	}

	res1 := createDraft("1", []byte("v1 content"))
	if res1.Code != http.StatusCreated {
		t.Fatalf("first create: %d %s", res1.Code, res1.Body.String())
	}
	var release1 store.Release
	decodeResponse(t, res1, &release1)

	// 2. Re-create same DRAFT (should update and return 201)
	res2 := createDraft("1", []byte("v1 updated content"))
	if res2.Code != http.StatusCreated {
		t.Fatalf("update draft: %d %s", res2.Code, res2.Body.String())
	}
	var release2 store.Release
	decodeResponse(t, res2, &release2)
	if release2.ReleaseID == release1.ReleaseID {
		t.Fatal("should have new releaseId after update")
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
		var multipartBody bytes.Buffer
		writer := multipart.NewWriter(&multipartBody)
		_ = writer.WriteField("payloadId", "core")
		_ = writer.WriteField("payloadVersion", version)
		_ = writer.WriteField("packageName", pkg)
		_ = writer.WriteField("versionCode", version)
		_ = writer.WriteField("certificateSha256", "cert")
		file, _ := writer.CreateFormFile("payload", "payload.bin")
		_, _ = file.Write([]byte("content"))
		_ = writer.Close()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/companies/acme/pack/releases", &multipartBody)
		req.Header.Set("Content-Type", writer.FormDataContentType())
		req.Header.Set("X-Company-Key", companyCreated.CompanyAPIKey)
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, req)
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
	if deleted.Code != http.StatusNoContent {
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
	if again.Code != http.StatusNoContent {
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
	if err := json.Unmarshal(response.Body.Bytes(), target); err != nil {
		t.Fatal(err)
	}
}
