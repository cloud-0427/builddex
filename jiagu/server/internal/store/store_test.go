package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"path/filepath"
	"sync"
	"testing"
	"time"
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

func TestManagerPrunesOnlyReleasedDatabases(t *testing.T) {
	manager, err := NewManagerWithOptions(t.TempDir(), ManagerOptions{
		MaxOpenDatabases: 1, DatabaseIdleTTL: time.Nanosecond,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	lease, err := manager.CreateCompany(context.Background(), CreateCompanyInput{
		CompanyID: "acme", ExtJSON: "{}", APIKeyHash: "key-hash",
	})
	if err != nil {
		t.Fatal(err)
	}
	if closed, err := manager.PruneIdle(time.Now().Add(time.Hour)); err != nil || closed != 0 {
		t.Fatalf("active database pruned: closed=%d err=%v", closed, err)
	}
	lease.Release()
	if closed, err := manager.PruneIdle(time.Now().Add(time.Hour)); err != nil || closed != 1 {
		t.Fatalf("released database not pruned: closed=%d err=%v", closed, err)
	}
	reopened, err := manager.Acquire(context.Background(), "acme")
	if err != nil {
		t.Fatal(err)
	}
	reopened.Release()
}

func TestManagerCoordinatesConcurrentAcquireByCompany(t *testing.T) {
	manager, err := NewManager(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	created, err := manager.CreateCompany(context.Background(), CreateCompanyInput{
		CompanyID: "acme", ExtJSON: "{}", APIKeyHash: "key-hash",
	})
	if err != nil {
		t.Fatal(err)
	}
	created.Release()
	if closed, err := manager.PruneIdle(time.Now().Add(time.Hour)); err != nil || closed != 1 {
		t.Fatalf("prepare closed database: closed=%d err=%v", closed, err)
	}

	const workers = 16
	start := make(chan struct{})
	results := make(chan *Lease, workers)
	errorsFound := make(chan error, workers)
	var wait sync.WaitGroup
	for range workers {
		wait.Add(1)
		go func() {
			defer wait.Done()
			<-start
			lease, err := manager.Acquire(context.Background(), "acme")
			if err != nil {
				errorsFound <- err
				return
			}
			results <- lease
		}()
	}
	close(start)
	wait.Wait()
	close(results)
	close(errorsFound)
	for err := range errorsFound {
		t.Fatal(err)
	}
	var expected *sql.DB
	count := 0
	for lease := range results {
		if expected == nil {
			expected = lease.DB
		} else if lease.DB != expected {
			t.Fatal("concurrent acquire opened multiple database handles")
		}
		lease.Release()
		count++
	}
	if count != workers {
		t.Fatalf("leases=%d, want %d", count, workers)
	}
}

func TestManagerEnforcesDatabaseCacheCapacity(t *testing.T) {
	manager, err := NewManagerWithOptions(t.TempDir(), ManagerOptions{
		MaxOpenDatabases: 2, DatabaseIdleTTL: time.Hour,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	for _, companyID := range []string{"company-1", "company-2", "company-3"} {
		lease, err := manager.CreateCompany(context.Background(), CreateCompanyInput{
			CompanyID: companyID, ExtJSON: "{}", APIKeyHash: "key-" + companyID,
		})
		if err != nil {
			t.Fatal(err)
		}
		lease.Release()
		time.Sleep(time.Millisecond)
	}
	manager.mu.Lock()
	openDatabases := len(manager.dbs)
	_, oldestStillOpen := manager.dbs["company-1"]
	manager.mu.Unlock()
	if openDatabases != 2 || oldestStillOpen {
		t.Fatalf("open databases=%d oldestStillOpen=%v", openDatabases, oldestStillOpen)
	}
}

func TestCleanupExpiredChallengesUsesBatchLimit(t *testing.T) {
	manager, err := NewManager(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	lease, err := manager.CreateCompany(context.Background(), CreateCompanyInput{
		CompanyID: "acme", ExtJSON: "{}", APIKeyHash: "key-hash",
	})
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().Unix()
	for _, value := range []struct {
		id      string
		expires int64
	}{{"expired-1", now - 3}, {"expired-2", now - 2}, {"expired-3", now - 1}, {"future", now + 60}} {
		if err := CreateChallenge(context.Background(), lease.DB, value.id, value.id, "ENROLL", value.expires); err != nil {
			t.Fatal(err)
		}
	}
	lease.Release()
	deleted, databases, err := manager.CleanupExpiredChallenges(context.Background(), 2)
	if err != nil || deleted != 2 || databases != 1 {
		t.Fatalf("first cleanup: deleted=%d databases=%d err=%v", deleted, databases, err)
	}
	deleted, _, err = manager.CleanupExpiredChallenges(context.Background(), 2)
	if err != nil || deleted != 1 {
		t.Fatalf("second cleanup: deleted=%d err=%v", deleted, err)
	}
	check, err := manager.Acquire(context.Background(), "acme")
	if err != nil {
		t.Fatal(err)
	}
	defer check.Release()
	var remaining int
	if err := check.DB.QueryRow(`SELECT COUNT(*) FROM challenges`).Scan(&remaining); err != nil || remaining != 1 {
		t.Fatalf("remaining challenges=%d err=%v", remaining, err)
	}
}

func TestReleaseCursorPaginationHasNoDuplicates(t *testing.T) {
	manager, err := NewManager(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()
	lease, err := manager.CreateCompany(context.Background(), CreateCompanyInput{
		CompanyID: "acme", ExtJSON: "{}", APIKeyHash: "key-hash",
	})
	if err != nil {
		t.Fatal(err)
	}
	defer lease.Release()
	for i := 1; i <= 5; i++ {
		release := NewRelease{Release: Release{
			ReleaseID: fmt.Sprintf("release-%d", i), PayloadID: "app-main", PayloadVersion: int64(i),
			PackageName: fmt.Sprintf("com.example.%d", i), VersionCode: int64(i), CertificateDigestsJSON: "[]",
			PayloadKeyCiphertext: []byte{byte(i)}, PayloadKeyVersion: 1,
		}}
		if err := CreateRelease(context.Background(), lease.DB, release); err != nil {
			t.Fatal(err)
		}
	}
	first, more, err := ListReleasesPage(context.Background(), lease.DB, 0, "", 2)
	if err != nil || len(first) != 2 || !more {
		t.Fatalf("first page: len=%d more=%v err=%v", len(first), more, err)
	}
	second, more, err := ListReleasesPage(context.Background(), lease.DB, first[1].CreatedAt, first[1].ReleaseID, 2)
	if err != nil || len(second) != 2 || !more {
		t.Fatalf("second page: len=%d more=%v err=%v", len(second), more, err)
	}
	third, more, err := ListReleasesPage(context.Background(), lease.DB, second[1].CreatedAt, second[1].ReleaseID, 2)
	if err != nil || len(third) != 1 || more {
		t.Fatalf("third page: len=%d more=%v err=%v", len(third), more, err)
	}
	seen := map[string]bool{}
	for _, page := range [][]Release{first, second, third} {
		for _, release := range page {
			if seen[release.ReleaseID] {
				t.Fatalf("duplicate release %s", release.ReleaseID)
			}
			seen[release.ReleaseID] = true
		}
	}
}

func TestDraftDeliveryChargesQuotaOnlyOncePerRelease(t *testing.T) {
	ctx := context.Background()
	manager, err := NewManager(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer manager.Close()

	lease, err := manager.CreateCompany(ctx, CreateCompanyInput{
		CompanyID: "acme", DeliveryLimit: 2, ExtJSON: "{}", APIKeyHash: "key-hash",
	})
	if err != nil {
		t.Fatal(err)
	}
	defer lease.Release()
	db := lease.DB
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

	if _, changed, err := SetReleaseStatus(ctx, db, release.ReleaseID, "PUBLISHED"); err != nil || !changed {
		t.Fatalf("first publish: changed=%v err=%v", changed, err)
	}
	if _, changed, err := SetReleaseStatus(ctx, db, release.ReleaseID, "PUBLISHED"); err != nil || changed {
		t.Fatalf("repeated publish: changed=%v err=%v", changed, err)
	}
	if err := UpdateReleasePacker(ctx, db, release.ReleaseID, "different-host"); err != nil {
		t.Fatal(err)
	}
	stored, _ = GetRelease(ctx, db, release.ReleaseID, false)
	if stored.Packer != "build-host" {
		t.Fatalf("published release packer changed: %q", stored.Packer)
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
