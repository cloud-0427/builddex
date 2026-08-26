package logging

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

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
