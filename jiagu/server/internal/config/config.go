package config

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type LoggingConfig struct {
	Level       string
	Format      string
	Console     bool
	FileEnabled bool
	Directory   string
	FilePrefix  string
	MaxSizeMB   int
	MaxAgeDays  int
	MaxBackups  int
	Compress    bool
	LocalTime   bool
}

type Config struct {
	Environment         string
	ListenAddr          string
	DataDir             string
	AdminToken          string
	MasterKey           []byte
	MaxPayloadBytes     int64
	ChallengeTTL        time.Duration
	GrantTTL            time.Duration
	DeviceCredentialTTL time.Duration
	IntegrityMode       string
	GoogleCredential    string
	UsingDevAdminToken  bool
	UsingDevMasterKey   bool
	Logging             LoggingConfig
}

type Options struct {
	Environment string
	ConfigDir   string
}

const developmentAdminToken = "local-debug-admin-token-change-me"
const developmentMasterKey = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"

// Load retains the environment-only behavior used by tests and embedders.
func Load() (Config, error) { return LoadWithOptions(Options{}) }

// LoadWithOptions applies defaults, common JSON, environment JSON, then environment variables.
func LoadWithOptions(options Options) (Config, error) {
	environment := strings.ToLower(strings.TrimSpace(options.Environment))
	if environment == "" {
		environment = strings.ToLower(envOr("JIAGU_ENV", "dev"))
	}
	cfg := Config{
		Environment:         environment,
		ListenAddr:          ":8761",
		DataDir:             "data/companies",
		MaxPayloadBytes:     64 << 20,
		ChallengeTTL:        180 * time.Second,
		GrantTTL:            600 * time.Second,
		DeviceCredentialTTL: 30 * 24 * time.Hour,
		IntegrityMode:       "disabled",
		Logging: LoggingConfig{
			Level: "info", Format: "text", Console: true, FileEnabled: true,
			Directory: "logs", FilePrefix: "jiagu-server", MaxSizeMB: 50,
			MaxAgeDays: 2, MaxBackups: 10, Compress: true, LocalTime: true,
		},
	}
	if options.ConfigDir != "" {
		if err := applyConfigFile(&cfg, filepath.Join(options.ConfigDir, "application.json")); err != nil {
			return Config{}, err
		}
		if err := applyConfigFile(&cfg, filepath.Join(options.ConfigDir, "application."+environment+".json")); err != nil {
			return Config{}, err
		}
	}
	applyEnvironment(&cfg)
	return validate(cfg)
}

type fileConfig struct {
	ListenAddr                 *string            `json:"listenAddr"`
	DataDir                    *string            `json:"dataDir"`
	MaxPayloadMB               *int               `json:"maxPayloadMB"`
	ChallengeTTLSeconds        *int               `json:"challengeTTLSeconds"`
	GrantTTLSeconds            *int               `json:"grantTTLSeconds"`
	DeviceCredentialTTLSeconds *int               `json:"deviceCredentialTTLSeconds"`
	IntegrityMode              *string            `json:"integrityMode"`
	Logging                    *fileLoggingConfig `json:"logging"`
}

type fileLoggingConfig struct {
	Level       *string `json:"level"`
	Format      *string `json:"format"`
	Console     *bool   `json:"console"`
	FileEnabled *bool   `json:"fileEnabled"`
	Directory   *string `json:"directory"`
	FilePrefix  *string `json:"filePrefix"`
	MaxSizeMB   *int    `json:"maxSizeMB"`
	MaxAgeDays  *int    `json:"maxAgeDays"`
	MaxBackups  *int    `json:"maxBackups"`
	Compress    *bool   `json:"compress"`
	LocalTime   *bool   `json:"localTime"`
}

func applyConfigFile(cfg *Config, path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("read config file %s: %w", path, err)
	}
	var values fileConfig
	decoder := json.NewDecoder(strings.NewReader(string(data)))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&values); err != nil {
		return fmt.Errorf("parse config file %s: %w", path, err)
	}
	if values.ListenAddr != nil {
		cfg.ListenAddr = *values.ListenAddr
	}
	if values.DataDir != nil {
		cfg.DataDir = *values.DataDir
	}
	if values.MaxPayloadMB != nil {
		cfg.MaxPayloadBytes = int64(*values.MaxPayloadMB) << 20
	}
	if values.ChallengeTTLSeconds != nil {
		cfg.ChallengeTTL = time.Duration(*values.ChallengeTTLSeconds) * time.Second
	}
	if values.GrantTTLSeconds != nil {
		cfg.GrantTTL = time.Duration(*values.GrantTTLSeconds) * time.Second
	}
	if values.DeviceCredentialTTLSeconds != nil {
		cfg.DeviceCredentialTTL = time.Duration(*values.DeviceCredentialTTLSeconds) * time.Second
	}
	if values.IntegrityMode != nil {
		cfg.IntegrityMode = strings.ToLower(*values.IntegrityMode)
	}
	if values.Logging != nil {
		logging := values.Logging
		if logging.Level != nil {
			cfg.Logging.Level = strings.ToLower(*logging.Level)
		}
		if logging.Format != nil {
			cfg.Logging.Format = strings.ToLower(*logging.Format)
		}
		if logging.Console != nil {
			cfg.Logging.Console = *logging.Console
		}
		if logging.FileEnabled != nil {
			cfg.Logging.FileEnabled = *logging.FileEnabled
		}
		if logging.Directory != nil {
			cfg.Logging.Directory = *logging.Directory
		}
		if logging.FilePrefix != nil {
			cfg.Logging.FilePrefix = *logging.FilePrefix
		}
		if logging.MaxSizeMB != nil {
			cfg.Logging.MaxSizeMB = *logging.MaxSizeMB
		}
		if logging.MaxAgeDays != nil {
			cfg.Logging.MaxAgeDays = *logging.MaxAgeDays
		}
		if logging.MaxBackups != nil {
			cfg.Logging.MaxBackups = *logging.MaxBackups
		}
		if logging.Compress != nil {
			cfg.Logging.Compress = *logging.Compress
		}
		if logging.LocalTime != nil {
			cfg.Logging.LocalTime = *logging.LocalTime
		}
	}
	return nil
}

func applyEnvironment(cfg *Config) {
	if value := strings.TrimSpace(os.Getenv("JIAGU_LISTEN_ADDR")); value != "" {
		cfg.ListenAddr = value
	}
	if value := strings.TrimSpace(os.Getenv("JIAGU_DATA_DIR")); value != "" {
		cfg.DataDir = value
	}
	if value, ok := envPositiveInt("JIAGU_MAX_PAYLOAD_MB"); ok {
		cfg.MaxPayloadBytes = int64(value) << 20
	}
	if value, ok := envPositiveInt("JIAGU_CHALLENGE_TTL_SECONDS"); ok {
		cfg.ChallengeTTL = time.Duration(value) * time.Second
	}
	if value, ok := envPositiveInt("JIAGU_GRANT_TTL_SECONDS"); ok {
		cfg.GrantTTL = time.Duration(value) * time.Second
	}
	if value, ok := envPositiveInt("JIAGU_DEVICE_CREDENTIAL_TTL_SECONDS"); ok {
		cfg.DeviceCredentialTTL = time.Duration(value) * time.Second
	}
	if value := strings.TrimSpace(os.Getenv("JIAGU_INTEGRITY_MODE")); value != "" {
		cfg.IntegrityMode = strings.ToLower(value)
	}
	if value := strings.TrimSpace(os.Getenv("JIAGU_LOG_LEVEL")); value != "" {
		cfg.Logging.Level = strings.ToLower(value)
	}
	if value := strings.TrimSpace(os.Getenv("JIAGU_LOG_FORMAT")); value != "" {
		cfg.Logging.Format = strings.ToLower(value)
	}
	if value := strings.TrimSpace(os.Getenv("JIAGU_LOG_DIR")); value != "" {
		cfg.Logging.Directory = value
	}
	if value, ok := envPositiveInt("JIAGU_LOG_MAX_SIZE_MB"); ok {
		cfg.Logging.MaxSizeMB = value
	}
	if value, ok := envPositiveInt("JIAGU_LOG_MAX_AGE_DAYS"); ok {
		cfg.Logging.MaxAgeDays = value
	}
	if value, ok := envPositiveInt("JIAGU_LOG_RETENTION_DAYS"); ok {
		cfg.Logging.MaxAgeDays = value
	}
	if value, ok := envPositiveInt("JIAGU_LOG_MAX_BACKUPS"); ok {
		cfg.Logging.MaxBackups = value
	}
	if value, ok := envBool("JIAGU_LOG_COMPRESS"); ok {
		cfg.Logging.Compress = value
	}
	if value, ok := envBool("JIAGU_LOG_LOCAL_TIME"); ok {
		cfg.Logging.LocalTime = value
	}
	if value, ok := envBool("JIAGU_LOG_CONSOLE"); ok {
		cfg.Logging.Console = value
	}
	if value, ok := envBool("JIAGU_LOG_FILE_ENABLED"); ok {
		cfg.Logging.FileEnabled = value
	}
	cfg.AdminToken = os.Getenv("JIAGU_ADMIN_TOKEN")
	cfg.GoogleCredential = os.Getenv("GOOGLE_APPLICATION_CREDENTIALS")
}

func validate(cfg Config) (Config, error) {
	if cfg.IntegrityMode != "disabled" && cfg.IntegrityMode != "google" {
		return Config{}, fmt.Errorf("unsupported integrityMode %q", cfg.IntegrityMode)
	}
	if cfg.Logging.Level != "debug" && cfg.Logging.Level != "info" && cfg.Logging.Level != "warn" && cfg.Logging.Level != "error" {
		return Config{}, fmt.Errorf("unsupported logging.level %q", cfg.Logging.Level)
	}
	if cfg.Logging.Format != "text" && cfg.Logging.Format != "json" {
		return Config{}, fmt.Errorf("unsupported logging.format %q", cfg.Logging.Format)
	}
	if cfg.Logging.MaxSizeMB <= 0 || cfg.Logging.MaxAgeDays <= 0 || cfg.Logging.MaxBackups < 0 ||
		cfg.Logging.FilePrefix == "" || (cfg.Logging.FileEnabled && cfg.Logging.Directory == "") {
		return Config{}, errors.New("invalid logging maxSizeMB, maxAgeDays, maxBackups, filePrefix, or directory")
	}
	if cfg.AdminToken == "" {
		if cfg.IntegrityMode != "disabled" {
			return Config{}, errors.New("JIAGU_ADMIN_TOKEN is required when integrityMode=google")
		}
		cfg.AdminToken = developmentAdminToken
		cfg.UsingDevAdminToken = true
	}
	encoded := os.Getenv("JIAGU_MASTER_KEY_B64")
	if encoded == "" {
		if cfg.IntegrityMode != "disabled" {
			return Config{}, errors.New("JIAGU_MASTER_KEY_B64 is required when integrityMode=google")
		}
		cfg.MasterKey = []byte(developmentMasterKey)
		cfg.UsingDevMasterKey = true
		return cfg, nil
	}
	key, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil || len(key) < 32 {
		return Config{}, errors.New("JIAGU_MASTER_KEY_B64 must decode to at least 32 bytes")
	}
	cfg.MasterKey = key
	return cfg, nil
}

func envOr(name, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(name)); value != "" {
		return value
	}
	return fallback
}

func envPositiveInt(name string) (int, bool) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return 0, false
	}
	parsed, err := strconv.Atoi(value)
	return parsed, err == nil && parsed > 0
}

func envBool(name string) (bool, bool) {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		return false, false
	}
	parsed, err := strconv.ParseBool(value)
	return parsed, err == nil
}
