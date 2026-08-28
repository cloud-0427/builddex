package store

import (
	"context"
	"errors"
	"path/filepath"
	"sync"
	"testing"
)

func TestInitializeRejectsUnsupportedSchema(t *testing.T) {
	db, err := openSQLite(filepath.Join(t.TempDir(), "old.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Exec(`CREATE TABLE schema_meta(schema_version INTEGER NOT NULL, updated_at INTEGER NOT NULL);
		INSERT INTO schema_meta VALUES(5, unixepoch())`); err != nil {
		t.Fatal(err)
	}
	if err := initializeSchema(context.Background(), db); err == nil {
		t.Fatal("schema version 5 must be rejected instead of migrated")
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
		PayloadKeyCiphertext: []byte{2}, PayloadKeyVersion: 1,
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
