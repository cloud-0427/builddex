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
		zap.Int64("integrityCloudProjectNumber", cfg.IntegrityCloudProjectNumber),
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
		zap.Bool("logRotateDaily", cfg.Logging.RotateDaily),
		zap.Float64("logSuccessSampleRate", cfg.Logging.SuccessSampleRate),
		zap.Duration("logSlowRequestThreshold", cfg.Logging.SlowRequestThreshold),
		zap.Bool("logCompress", cfg.Logging.Compress),
		zap.Duration("challengeCleanupInterval", cfg.ChallengeCleanupInterval),
		zap.Int("challengeCleanupBatchSize", cfg.ChallengeCleanupBatchSize),
		zap.Int("maxOpenCompanyDatabases", cfg.MaxOpenCompanyDatabases),
		zap.Duration("companyDatabaseIdleTTL", cfg.CompanyDatabaseIdleTTL),
		zap.Int("databaseMaxOpenConns", cfg.DatabaseMaxOpenConns),
		zap.Int("databaseMaxIdleConns", cfg.DatabaseMaxIdleConns),
	)

	dbs, err := store.NewManagerWithOptions(cfg.DataDir, store.ManagerOptions{
		MaxOpenDatabases: cfg.MaxOpenCompanyDatabases,
		DatabaseIdleTTL:  cfg.CompanyDatabaseIdleTTL,
		MaxOpenConns:     cfg.DatabaseMaxOpenConns,
		MaxIdleConns:     cfg.DatabaseMaxIdleConns,
		ConnMaxIdleTime:  cfg.DatabaseConnMaxIdleTime,
		ConnMaxLifetime:  cfg.DatabaseConnMaxLifetime,
	})
	if err != nil {
		logger.Error("initialize storage", zap.Error(err))
		os.Exit(1)
	}
	defer dbs.Close()
	maintenanceCtx, stopMaintenance := context.WithCancel(context.Background())
	maintenanceDone := make(chan struct{})
	go func() {
		defer close(maintenanceDone)
		runMaintenance(maintenanceCtx, logger, dbs, cfg.ChallengeCleanupInterval, cfg.ChallengeCleanupBatchSize)
	}()
	defer func() {
		stopMaintenance()
		<-maintenanceDone
	}()

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
		stopMaintenance()
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

func runMaintenance(ctx context.Context, logger *zap.Logger, dbs *store.Manager, interval time.Duration, challengeBatchSize int) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			operationCtx, cancel := context.WithTimeout(ctx, min(interval, 30*time.Second))
			deleted, databases, cleanupErr := dbs.CleanupExpiredChallenges(operationCtx, challengeBatchSize)
			closed, pruneErr := dbs.PruneIdle(time.Now())
			cancel()
			if cleanupErr != nil || pruneErr != nil {
				logger.Warn("storage maintenance completed with errors", zap.Error(errors.Join(cleanupErr, pruneErr)),
					zap.Int64("challengesDeleted", deleted), zap.Int("databasesScanned", databases),
					zap.Int("databasesClosed", closed))
			} else if deleted > 0 || closed > 0 {
				logger.Info("storage maintenance completed", zap.Int64("challengesDeleted", deleted),
					zap.Int("databasesScanned", databases), zap.Int("databasesClosed", closed))
			}
		}
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
