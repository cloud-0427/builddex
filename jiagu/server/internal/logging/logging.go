package logging

import (
	"errors"
	"os"
	"path/filepath"
	"strings"

	"github.com/xjc/jiagu/server/internal/config"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"gopkg.in/natefinch/lumberjack.v2"
)

// Configure installs Zap as the process-wide logger and Lumberjack as its rolling file sink.
// The returned cleanup function flushes Zap, closes Lumberjack, and restores global loggers.
func Configure(cfg config.LoggingConfig) (*zap.Logger, func(), error) {
	level, err := parseLevel(cfg.Level)
	if err != nil {
		return nil, nil, err
	}
	encoderConfig := zap.NewProductionEncoderConfig()
	encoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	encoderConfig.EncodeDuration = zapcore.StringDurationEncoder
	encoderConfig.EncodeLevel = zapcore.LowercaseLevelEncoder

	newEncoder := func() zapcore.Encoder {
		if cfg.Format == "json" {
			return zapcore.NewJSONEncoder(encoderConfig)
		}
		return zapcore.NewConsoleEncoder(encoderConfig)
	}

	var cores []zapcore.Core
	if cfg.Console {
		cores = append(cores, zapcore.NewCore(newEncoder(), zapcore.Lock(os.Stdout), level))
	}
	var rollingFile *lumberjack.Logger
	if cfg.FileEnabled {
		if filepath.Base(cfg.FilePrefix) != cfg.FilePrefix || strings.ContainsAny(cfg.FilePrefix, `/\`) {
			return nil, nil, errors.New("log filePrefix must be a plain file name")
		}
		absoluteDirectory, err := filepath.Abs(cfg.Directory)
		if err != nil {
			return nil, nil, err
		}
		if err := os.MkdirAll(absoluteDirectory, 0o750); err != nil {
			return nil, nil, err
		}
		rollingFile = &lumberjack.Logger{
			Filename:   filepath.Join(absoluteDirectory, cfg.FilePrefix+".log"),
			MaxSize:    cfg.MaxSizeMB,
			MaxAge:     cfg.MaxAgeDays,
			MaxBackups: cfg.MaxBackups,
			Compress:   cfg.Compress,
			LocalTime:  cfg.LocalTime,
		}
		cores = append(cores, zapcore.NewCore(newEncoder(), zapcore.AddSync(rollingFile), level))
	}

	logger := zap.New(zapcore.NewTee(cores...), zap.AddCaller(), zap.AddStacktrace(zapcore.ErrorLevel))
	restoreGlobal := zap.ReplaceGlobals(logger)
	restoreStandard, err := zap.RedirectStdLogAt(logger, zapcore.InfoLevel)
	if err != nil {
		restoreGlobal()
		if rollingFile != nil {
			_ = rollingFile.Close()
		}
		return nil, nil, err
	}
	cleanup := func() {
		restoreStandard()
		restoreGlobal()
		_ = logger.Sync()
		if rollingFile != nil {
			_ = rollingFile.Close()
		}
	}
	return logger, cleanup, nil
}

func parseLevel(value string) (zapcore.Level, error) {
	var level zapcore.Level
	if err := level.UnmarshalText([]byte(strings.ToLower(value))); err != nil {
		return zapcore.InfoLevel, err
	}
	return level, nil
}
