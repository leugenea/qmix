package server

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"io"
)

const hostTokenBytes = 32

func newHostToken(random io.Reader) (string, error) {
	entropy := make([]byte, hostTokenBytes)
	if _, err := io.ReadFull(random, entropy); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(entropy), nil
}

func hostTokensEqual(supplied, stored string) bool {
	suppliedHash := sha256.Sum256([]byte(supplied))
	storedHash := sha256.Sum256([]byte(stored))
	return subtle.ConstantTimeCompare(suppliedHash[:], storedHash[:]) == 1
}
