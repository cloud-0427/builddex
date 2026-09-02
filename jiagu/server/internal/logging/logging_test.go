package logging

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/xjc/jiagu/server/internal/config"
	"go.uber.org/zap"
)

func TestConfigureWritesThroughZapAndLumberjack(t *testing.T) {
	directory := t.TempDir()
	logger, cleanup, err := Configure(config.LoggingConfig{
		Level: "debug", Format: "json", Console: false, FileEnabled: true,
		Directory: directory, FilePrefix: "server", MaxSizeMB: 1,
		MaxAgeDays: 2, MaxBackups: 3, Compress: true, LocalTime: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	logger.Info("test message", zap.String("requestId", "request-1"))
	cleanup()
	data, err := os.ReadFile(filepath.Join(directory, "server.log"))
	if err != nil {
		t.Fatal(err)
	}
	text := string(data)
	if !strings.Contains(text, `"msg":"test message"`) || !strings.Contains(text, `"requestId":"request-1"`) {
		t.Fatalf("unexpected log output: %s", text)
	}
}

func TestConfigureRotatesActiveLogFromPreviousNaturalDay(t *testing.T) {
	directory := t.TempDir()
	activeLog := filepath.Join(directory, "server.log")
	if err := os.WriteFile(activeLog, []byte("previous day\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	yesterday := time.Now().Add(-24 * time.Hour)
	if err := os.Chtimes(activeLog, yesterday, yesterday); err != nil {
		t.Fatal(err)
	}

	logger, cleanup, err := Configure(config.LoggingConfig{
		Level: "info", Format: "json", FileEnabled: true,
		Directory: directory, FilePrefix: "server", MaxSizeMB: 3072,
		MaxAgeDays: 2, MaxBackups: 10, RotateDaily: true, LocalTime: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	logger.Info("current day")
	cleanup()

	backups, err := filepath.Glob(filepath.Join(directory, "server-*.log"))
	if err != nil {
		t.Fatal(err)
	}
	if len(backups) != 1 {
		t.Fatalf("expected one previous-day backup, got %v", backups)
	}
	backupData, err := os.ReadFile(backups[0])
	if err != nil {
		t.Fatal(err)
	}
	if string(backupData) != "previous day\n" {
		t.Fatalf("unexpected backup contents: %q", backupData)
	}
	activeData, err := os.ReadFile(activeLog)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(activeData), `"msg":"current day"`) {
		t.Fatalf("current-day message missing: %s", activeData)
	}
}

func TestNextMidnightUsesNaturalDayInLocation(t *testing.T) {
	location := time.FixedZone("UTC+8", 8*60*60)
	now := time.Date(2026, time.September, 2, 23, 59, 59, 0, location)
	want := time.Date(2026, time.September, 3, 0, 0, 0, 0, location)
	if got := nextMidnight(now); !got.Equal(want) {
		t.Fatalf("next midnight: got %s, want %s", got, want)
	}
}
