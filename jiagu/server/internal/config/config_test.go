package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestDisabledModeUsesDevelopmentDefaults(t *testing.T) {
	t.Setenv("JIAGU_INTEGRITY_MODE", "disabled")
	t.Setenv("JIAGU_ADMIN_TOKEN", "")
	t.Setenv("JIAGU_MASTER_KEY_B64", "")

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.UsingDevAdminToken || !cfg.UsingDevMasterKey {
		t.Fatal("expected development defaults")
	}
	if cfg.AdminToken == "" || len(cfg.MasterKey) != 32 {
		t.Fatal("development credentials were not initialized")
	}
	if cfg.GrantTTL != 7*24*time.Hour {
		t.Fatalf("unexpected default grant TTL: %s", cfg.GrantTTL)
	}
}

func TestProfileFilesAndEnvironmentOverride(t *testing.T) {
	directory := t.TempDir()
	if err := os.WriteFile(filepath.Join(directory, "application.json"), []byte(`{
        "listenAddr": ":9000",
        "logging": {"maxAgeDays": 5}
    }`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(directory, "application.dev.json"), []byte(`{
        "dataDir": "data/from-dev",
        "integrityMode": "disabled",
        "logging": {"level": "debug", "directory": "logs/from-dev"}
    }`), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("JIAGU_LISTEN_ADDR", ":9100")
	t.Setenv("JIAGU_ADMIN_TOKEN", "")
	t.Setenv("JIAGU_MASTER_KEY_B64", "")

	cfg, err := LoadWithOptions(Options{Environment: "dev", ConfigDir: directory})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ListenAddr != ":9100" || cfg.DataDir != "data/from-dev" || cfg.Logging.MaxAgeDays != 5 || cfg.Logging.Level != "debug" {
		t.Fatalf("unexpected merged config: %+v", cfg)
	}
}

func TestGoogleModeRequiresSecureConfiguration(t *testing.T) {
	t.Setenv("JIAGU_INTEGRITY_MODE", "google")
	t.Setenv("JIAGU_ADMIN_TOKEN", "")
	t.Setenv("JIAGU_MASTER_KEY_B64", "")

	_, err := Load()
	if err == nil || !strings.Contains(err.Error(), "JIAGU_ADMIN_TOKEN") {
		t.Fatalf("expected missing admin token error, got %v", err)
	}
}
