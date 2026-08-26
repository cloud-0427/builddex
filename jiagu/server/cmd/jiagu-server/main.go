package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/xjc/jiagu/server/internal/config"
	"github.com/xjc/jiagu/server/internal/httpapi"
	serverlogging "github.com/xjc/jiagu/server/internal/logging"
	"github.com/xjc/jiagu/server/internal/store"
	"go.uber.org/zap"
)

// buildEnvironment can be set with: go build -ldflags "-X main.buildEnvironment=prod".
var buildEnvironment string

func main() {
	environment := flag.String("env", buildEnvironment, "configuration environment: dev, test, or prod")
	configDir := flag.String("config-dir", "config", "directory containing application configuration files")
	flag.Parse()

	cfg, err := config.LoadWithOptions(config.Options{Environment: *environment, ConfigDir: *configDir})
	if err != nil {
		log.Printf("invalid configuration: %v", err)
		os.Exit(1)
	}
	logger, cleanupLogging, err := serverlogging.Configure(cfg.Logging)
	if err != nil {
		log.Printf("initialize logging: %v", err)
		os.Exit(1)
	}
	defer cleanupLogging()
	if cfg.UsingDevAdminToken {
		logger.Warn("using insecure default admin token because integrity mode is disabled",
			zap.String("adminTokenFingerprint", fingerprint([]byte(cfg.AdminToken))))
	}
	if cfg.UsingDevMasterKey {
		logger.Warn("using deterministic development master key because integrity mode is disabled; do not use this database in production")
	}
	logger.Info("effective configuration",
		zap.String("environment", cfg.Environment),
		zap.String("listenAddr", cfg.ListenAddr),
		zap.String("dataDir", cfg.DataDir),
		zap.Int64("maxPayloadMB", cfg.MaxPayloadBytes>>20),
		zap.Duration("challengeTTL", cfg.ChallengeTTL),
		zap.Duration("grantTTL", cfg.GrantTTL),
		zap.Duration("deviceCredentialTTL", cfg.DeviceCredentialTTL),
		zap.String("integrityMode", cfg.IntegrityMode),
		zap.Bool("googleCredentialsConfigured", cfg.GoogleCredential != ""),
		zap.String("adminTokenSource", configSource(cfg.UsingDevAdminToken)),
		zap.String("adminTokenFingerprint", fingerprint([]byte(cfg.AdminToken))),
		zap.String("masterKeySource", configSource(cfg.UsingDevMasterKey)),
		zap.String("masterKeyFingerprint", fingerprint(cfg.MasterKey)),
		zap.String("logLibrary", "zap+lumberjack"),
		zap.String("logLevel", cfg.Logging.Level),
		zap.String("logFormat", cfg.Logging.Format),
		zap.Bool("logConsole", cfg.Logging.Console),
		zap.Bool("logFileEnabled", cfg.Logging.FileEnabled),
		zap.String("logDirectory", cfg.Logging.Directory),
		zap.Int("logMaxSizeMB", cfg.Logging.MaxSizeMB),
		zap.Int("logMaxAgeDays", cfg.Logging.MaxAgeDays),
		zap.Int("logMaxBackups", cfg.Logging.MaxBackups),
		zap.Bool("logCompress", cfg.Logging.Compress),
	)

	dbs, err := store.NewManager(cfg.DataDir)
	if err != nil {
		logger.Error("initialize storage", zap.Error(err))
		os.Exit(1)
	}
	defer dbs.Close()

	handler := httpapi.New(cfg, dbs)
	server := &http.Server{
		Addr:              cfg.ListenAddr,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      5 * time.Minute,
		IdleTimeout:       60 * time.Second,
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-stop
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		_ = server.Shutdown(ctx)
	}()

	logger.Info("jiagu server started", zap.String("address", cfg.ListenAddr),
		zap.String("dataDir", cfg.DataDir), zap.String("integrityMode", cfg.IntegrityMode))
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		logger.Error("http server stopped", zap.Error(err))
		os.Exit(1)
	}
}

func configSource(usingDevelopmentDefault bool) string {
	if usingDevelopmentDefault {
		return "development-default"
	}
	return "environment"
}

func fingerprint(value []byte) string {
	digest := sha256.Sum256(value)
	return hex.EncodeToString(digest[:6])
}
