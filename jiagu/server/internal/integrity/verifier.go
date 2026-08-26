package integrity

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"golang.org/x/oauth2/google"
)

const playIntegrityScope = "https://www.googleapis.com/auth/playintegrity"

type Expected struct {
	RequestHash       string
	PackageName       string
	VersionCode       int64
	CertificateSHA256 string
}

type Verifier interface {
	Verify(context.Context, string, Expected) error
}

type DisabledVerifier struct{}

func (DisabledVerifier) Verify(context.Context, string, Expected) error { return nil }

type GoogleVerifier struct {
	client *http.Client
}

func NewGoogleVerifier(ctx context.Context) (*GoogleVerifier, error) {
	client, err := google.DefaultClient(ctx, playIntegrityScope)
	if err != nil {
		return nil, err
	}
	client.Timeout = 15 * time.Second
	return &GoogleVerifier{client: client}, nil
}

func (v *GoogleVerifier) Verify(ctx context.Context, token string, expected Expected) error {
	if token == "" {
		return errors.New("integrityToken is required")
	}
	body, _ := json.Marshal(map[string]string{"integrity_token": token})
	endpoint := "https://playintegrity.googleapis.com/v1/" + url.PathEscape(expected.PackageName) + ":decodeIntegrityToken"
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := v.client.Do(req)
	if err != nil {
		return fmt.Errorf("decode Play Integrity token: %w", err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("Play Integrity API returned %d", resp.StatusCode)
	}
	var decoded struct {
		TokenPayloadExternal struct {
			RequestDetails struct {
				RequestPackageName string `json:"requestPackageName"`
				RequestHash        string `json:"requestHash"`
				TimestampMillis    string `json:"timestampMillis"`
			} `json:"requestDetails"`
			AppIntegrity struct {
				AppRecognitionVerdict   string   `json:"appRecognitionVerdict"`
				PackageName             string   `json:"packageName"`
				CertificateSHA256Digest []string `json:"certificateSha256Digest"`
				VersionCode             string   `json:"versionCode"`
			} `json:"appIntegrity"`
			DeviceIntegrity struct {
				DeviceRecognitionVerdict []string `json:"deviceRecognitionVerdict"`
			} `json:"deviceIntegrity"`
		} `json:"tokenPayloadExternal"`
	}
	if err := json.Unmarshal(data, &decoded); err != nil {
		return err
	}
	payload := decoded.TokenPayloadExternal
	if payload.RequestDetails.RequestPackageName != expected.PackageName || payload.RequestDetails.RequestHash != expected.RequestHash {
		return errors.New("Play Integrity request binding mismatch")
	}
	timestamp, err := strconv.ParseInt(payload.RequestDetails.TimestampMillis, 10, 64)
	if err != nil || time.Since(time.UnixMilli(timestamp)) > 5*time.Minute || time.Until(time.UnixMilli(timestamp)) > time.Minute {
		return errors.New("Play Integrity verdict is stale")
	}
	if payload.AppIntegrity.AppRecognitionVerdict != "PLAY_RECOGNIZED" ||
		payload.AppIntegrity.PackageName != expected.PackageName || payload.AppIntegrity.VersionCode != strconv.FormatInt(expected.VersionCode, 10) {
		return errors.New("Play Integrity application verdict rejected")
	}
	if !contains(payload.AppIntegrity.CertificateSHA256Digest, expected.CertificateSHA256) {
		return errors.New("Play Integrity certificate digest mismatch")
	}
	if !contains(payload.DeviceIntegrity.DeviceRecognitionVerdict, "MEETS_DEVICE_INTEGRITY") {
		return errors.New("Play Integrity device verdict rejected")
	}
	return nil
}

func contains(values []string, expected string) bool {
	for _, value := range values {
		if value == expected {
			return true
		}
	}
	return false
}
