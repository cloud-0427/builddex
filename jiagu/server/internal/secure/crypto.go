package secure

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

var rawURL = base64.RawURLEncoding

func RandomToken(size int) (string, error) {
	value, err := RandomBytes(size)
	if err != nil {
		return "", err
	}
	return rawURL.EncodeToString(value), nil
}

func RandomBytes(size int) ([]byte, error) {
	value := make([]byte, size)
	if _, err := rand.Read(value); err != nil {
		return nil, err
	}
	return value, nil
}

func SHA256URL(value []byte) string {
	sum := sha256.Sum256(value)
	return rawURL.EncodeToString(sum[:])
}

func HMAC256(key []byte, domain string, values ...string) []byte {
	mac := hmac.New(sha256.New, key)
	mac.Write([]byte(domain))
	for _, value := range values {
		mac.Write([]byte{0})
		mac.Write([]byte(value))
	}
	return mac.Sum(nil)
}

func EncryptAESGCM(key, plaintext, aad []byte) ([]byte, error) {
	block, err := aes.NewCipher(key[:32])
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return nil, err
	}
	return append(nonce, gcm.Seal(nil, nonce, plaintext, aad)...), nil
}

func DecryptAESGCM(key, encrypted, aad []byte) ([]byte, error) {
	block, err := aes.NewCipher(key[:32])
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	if len(encrypted) < gcm.NonceSize()+gcm.Overhead() {
		return nil, errors.New("encrypted payload is too short")
	}
	nonce := encrypted[:gcm.NonceSize()]
	return gcm.Open(nil, nonce, encrypted[gcm.NonceSize():], aad)
}

func DeriveCompanyKey(master []byte, companyID, purpose string) []byte {
	return HMAC256(master, "jiagu-company-key-v1", companyID, purpose)
}

func DeriveDevicePayloadKey(master []byte, companyID string, values ...string) []byte {
	all := append([]string{companyID}, values...)
	return HMAC256(master, "jiagu-device-payload-v1", all...)
}

func CompanySigningKey(master []byte, companyID string) ed25519.PrivateKey {
	seed := HMAC256(master, "jiagu-company-signing-v1", companyID)
	return ed25519.NewKeyFromSeed(seed)
}

func SignJWS(privateKey ed25519.PrivateKey, keyID string, payload any) (string, error) {
	header, _ := json.Marshal(map[string]string{"alg": "EdDSA", "kid": keyID, "typ": "JWT"})
	body, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}
	signingInput := rawURL.EncodeToString(header) + "." + rawURL.EncodeToString(body)
	signature := ed25519.Sign(privateKey, []byte(signingInput))
	return signingInput + "." + rawURL.EncodeToString(signature), nil
}

func VerifyJWS(publicKey ed25519.PublicKey, token string, target any) error {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return errors.New("invalid JWS format")
	}
	signature, err := rawURL.DecodeString(parts[2])
	if err != nil || !ed25519.Verify(publicKey, []byte(parts[0]+"."+parts[1]), signature) {
		return errors.New("invalid JWS signature")
	}
	body, err := rawURL.DecodeString(parts[1])
	if err != nil {
		return err
	}
	return json.Unmarshal(body, target)
}

func ParseRSAPublicKey(encoded string) (*rsa.PublicKey, []byte, error) {
	der, err := rawURL.DecodeString(encoded)
	if err != nil {
		return nil, nil, fmt.Errorf("decode wrap public key: %w", err)
	}
	parsed, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, nil, fmt.Errorf("parse wrap public key: %w", err)
	}
	key, ok := parsed.(*rsa.PublicKey)
	if !ok || key.N.BitLen() < 2048 {
		return nil, nil, errors.New("wrap public key must be RSA with at least 2048 bits")
	}
	return key, der, nil
}

func ParseECDSAPublicKey(encoded string) (*ecdsa.PublicKey, []byte, error) {
	der, err := rawURL.DecodeString(encoded)
	if err != nil {
		return nil, nil, fmt.Errorf("decode signing public key: %w", err)
	}
	parsed, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, nil, fmt.Errorf("parse signing public key: %w", err)
	}
	key, ok := parsed.(*ecdsa.PublicKey)
	if !ok || key.Curve != elliptic.P256() {
		return nil, nil, errors.New("signing public key must be ECDSA P-256")
	}
	return key, der, nil
}

func VerifyECDSA(publicKey *ecdsa.PublicKey, message []byte, signatureURL string) error {
	signature, err := rawURL.DecodeString(signatureURL)
	if err != nil {
		return errors.New("invalid device signature encoding")
	}
	digest := sha256.Sum256(message)
	if !ecdsa.VerifyASN1(publicKey, digest[:], signature) {
		return errors.New("invalid device signature")
	}
	return nil
}

func WrapRSAOAEP(publicKey *rsa.PublicKey, key, label []byte) (string, error) {
	// Use SHA-1 for maximum compatibility with Android KeyStore RSA-OAEP implementation.
	wrapped, err := rsa.EncryptOAEP(sha1.New(), rand.Reader, publicKey, key, label)
	if err != nil {
		return "", err
	}
	return rawURL.EncodeToString(wrapped), nil
}

func PublicKeyURL(key ed25519.PublicKey) string { return rawURL.EncodeToString(key) }
