package store

import (
	"context"
	"errors"
	"path/filepath"
	"sync"
	"testing"
)

func TestSchemaV6MigrationRemovesServerPayloadBlob(t *testing.T) {
	ctx := context.Background()
	db, err := openSQLite(filepath.Join(t.TempDir(), "legacy.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	_, err = db.ExecContext(ctx, `
		CREATE TABLE schema_meta(schema_version INTEGER NOT NULL, updated_at INTEGER NOT NULL);
		INSERT INTO schema_meta VALUES(5, unixepoch());
		CREATE TABLE payload_releases (
			release_id TEXT PRIMARY KEY, payload_id TEXT NOT NULL, payload_version INTEGER NOT NULL,
			package_name TEXT NOT NULL, version_code INTEGER NOT NULL,
			certificate_sha256_digests_json TEXT NOT NULL, certificate_set_sha256 TEXT NOT NULL,
			business_dex_sha256 TEXT NOT NULL, resources_sha256 TEXT NOT NULL, native_libs_sha256 TEXT NOT NULL,
			release_build_sha256 TEXT NOT NULL, plaintext_sha256 TEXT NOT NULL,
			canonical_ciphertext_sha256 TEXT NOT NULL, canonical_payload BLOB NOT NULL,
			canonical_key_ciphertext BLOB NOT NULL, payload_key_version INTEGER NOT NULL,
			packer TEXT NOT NULL, delivery_count INTEGER NOT NULL DEFAULT 0,
			draft_delivery_charged INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL,
			created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
			published_at INTEGER NOT NULL DEFAULT 0, revoked_at INTEGER NOT NULL DEFAULT 0,
			UNIQUE(package_name, version_code));
		INSERT INTO payload_releases VALUES(
			'r1','app-main',1,'com.example',1,'[]','cert','dex','res','native','build','plain',
			'cipher',zeroblob(4194304),x'010203',1,'host',0,0,'DRAFT',1,1,0,0);`)
	if err != nil {
		t.Fatal(err)
	}
	if err := initializeSchema(ctx, db); err != nil {
		t.Fatal(err)
	}
	var legacyColumns, schemaVersion int
	if err := db.QueryRowContext(ctx, `SELECT COUNT(*) FROM pragma_table_info('payload_releases')
		WHERE name IN ('canonical_payload','canonical_ciphertext_sha256')`).Scan(&legacyColumns); err != nil {
		t.Fatal(err)
	}
	if err := db.QueryRowContext(ctx, `SELECT schema_version FROM schema_meta`).Scan(&schemaVersion); err != nil {
		t.Fatal(err)
	}
	release, err := GetRelease(ctx, db, "r1", false)
	if err != nil {
		t.Fatal(err)
	}
	if legacyColumns != 0 || schemaVersion != 6 || release.LocalPayloadSize != 0 ||
		release.PayloadKeyWrapVersion != 1 || len(release.PayloadKeyCiphertext) != 3 {
		t.Fatalf("migration result: legacy=%d version=%d release=%+v", legacyColumns, schemaVersion, release)
	}
}

func TestDraftDeliveryChargesQuotaOnlyOncePerRelease(t *testing.T) {
	ctx := context.Background()
	manager, err := NewManager(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()

	db, err := manager.CreateCompany(ctx, CreateCompanyInput{
		CompanyID: "acme", DeliveryLimit: 2, ExtJSON: "{}", APIKeyHash: "key-hash",
	})
	if err != nil {
		t.Fatal(err)
	}
	release := NewRelease{Release: Release{
		ReleaseID: "release-1", PayloadID: "app-main", PayloadVersion: 1,
		PackageName: "com.example", VersionCode: 1, CertificateDigestsJSON: "[]",
		LocalCiphertextSHA256: "cipher", LocalPayloadSize: 128,
		PayloadKeyCiphertext: []byte{2}, PayloadKeyWrapVersion: 3, PayloadKeyVersion: 1,
		Packer: "build-host",
	}}
	if err := CreateRelease(ctx, db, release); err != nil {
		t.Fatal(err)
	}

	if err := IncrementDelivery(ctx, db, release.ReleaseID); err != nil {
		t.Fatal(err)
	}
	if err := IncrementDelivery(ctx, db, release.ReleaseID); err != nil {
		t.Fatal(err)
	}
	company, err := GetCompany(ctx, db)
	if err != nil {
		t.Fatal(err)
	}
	stored, err := GetRelease(ctx, db, release.ReleaseID, false)
	if err != nil {
		t.Fatal(err)
	}
	if company.DeliveryCount != 1 || stored.DeliveryCount != 2 || !stored.DraftDeliveryCharged {
		t.Fatalf("draft deliveries: company=%d release=%d charged=%v",
			company.DeliveryCount, stored.DeliveryCount, stored.DraftDeliveryCharged)
	}
	var wait sync.WaitGroup
	errorsFound := make(chan error, 8)
	for range 8 {
		wait.Add(1)
		go func() {
			defer wait.Done()
			errorsFound <- IncrementDelivery(ctx, db, release.ReleaseID)
		}()
	}
	wait.Wait()
	close(errorsFound)
	for err := range errorsFound {
		if err != nil {
			t.Fatalf("concurrent draft delivery: %v", err)
		}
	}
	company, _ = GetCompany(ctx, db)
	stored, _ = GetRelease(ctx, db, release.ReleaseID, false)
	if company.DeliveryCount != 1 || stored.DeliveryCount != 10 {
		t.Fatalf("concurrent draft deliveries: company=%d release=%d", company.DeliveryCount, stored.DeliveryCount)
	}

	if _, err := SetReleaseStatus(ctx, db, release.ReleaseID, "PUBLISHED"); err != nil {
		t.Fatal(err)
	}
	if err := IncrementDelivery(ctx, db, release.ReleaseID); err != nil {
		t.Fatal(err)
	}
	if err := IncrementDelivery(ctx, db, release.ReleaseID); !errors.Is(err, ErrLimitExceeded) {
		t.Fatalf("published delivery past the limit: %v", err)
	}
	company, _ = GetCompany(ctx, db)
	stored, _ = GetRelease(ctx, db, release.ReleaseID, false)
	if company.DeliveryCount != 2 || stored.DeliveryCount != 11 {
		t.Fatalf("published delivery: company=%d release=%d", company.DeliveryCount, stored.DeliveryCount)
	}
	logs, total, err := ListPackLogs(ctx, db, 1, 5)
	if err != nil {
		t.Fatal(err)
	}
	if total != 1 || len(logs) != 1 || logs[0].DeliveryCount != 11 {
		t.Fatalf("release delivery statistics: total=%d logs=%+v", total, logs)
	}
}
