package logging

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

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
	stopDailyRotation := func() {}
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
		if cfg.RotateDaily {
			location := time.UTC
			if cfg.LocalTime {
				location = time.Local
			}
			if err := rotateIfFromPreviousDay(rollingFile, location, time.Now()); err != nil {
				_ = rollingFile.Close()
				return nil, nil, err
			}
			stopDailyRotation = scheduleDailyRotation(rollingFile, location, os.Stderr)
		}
		cores = append(cores, zapcore.NewCore(newEncoder(), zapcore.AddSync(rollingFile), level))
	}

	logger := zap.New(zapcore.NewTee(cores...), zap.AddCaller(), zap.AddStacktrace(zapcore.ErrorLevel))
	restoreGlobal := zap.ReplaceGlobals(logger)
	restoreStandard, err := zap.RedirectStdLogAt(logger, zapcore.InfoLevel)
	if err != nil {
		restoreGlobal()
		stopDailyRotation()
		if rollingFile != nil {
			_ = rollingFile.Close()
		}
		return nil, nil, err
	}
	cleanup := func() {
		restoreStandard()
		restoreGlobal()
		stopDailyRotation()
		_ = logger.Sync()
		if rollingFile != nil {
			_ = rollingFile.Close()
		}
	}
	return logger, cleanup, nil
}

func rotateIfFromPreviousDay(logger *lumberjack.Logger, location *time.Location, now time.Time) error {
	info, err := os.Stat(logger.Filename)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("inspect active log for daily rotation: %w", err)
	}
	if sameDay(info.ModTime().In(location), now.In(location)) {
		return nil
	}
	if err := logger.Rotate(); err != nil {
		return fmt.Errorf("rotate active log from previous day: %w", err)
	}
	return nil
}

func scheduleDailyRotation(logger *lumberjack.Logger, location *time.Location, errorOutput *os.File) func() {
	stop := make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			now := time.Now().In(location)
			timer := time.NewTimer(time.Until(nextMidnight(now)))
			select {
			case <-timer.C:
				if err := logger.Rotate(); err != nil {
					_, _ = fmt.Fprintf(errorOutput, "daily log rotation failed: %v\n", err)
				}
			case <-stop:
				if !timer.Stop() {
					select {
					case <-timer.C:
					default:
					}
				}
				return
			}
		}
	}()
	return func() {
		close(stop)
		<-done
	}
}

func sameDay(left, right time.Time) bool {
	leftYear, leftDay := left.Year(), left.YearDay()
	return leftYear == right.Year() && leftDay == right.YearDay()
}

func nextMidnight(now time.Time) time.Time {
	year, month, day := now.Date()
	return time.Date(year, month, day+1, 0, 0, 0, 0, now.Location())
}

func parseLevel(value string) (zapcore.Level, error) {
	var level zapcore.Level
	if err := level.UnmarshalText([]byte(strings.ToLower(value))); err != nil {
		return zapcore.InfoLevel, err
	}
	return level, nil
}
