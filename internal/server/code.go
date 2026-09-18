package server

import (
	"math/rand"
	"strings"
)

// codeAlphabet is the set of characters allowed in a room code. Ambiguous
// characters (0, o, 1, l) are excluded so codes are easy to read and type.
const codeAlphabet = "abcdefghjkmnpqrstuvwxyz23456789"

// codeLength is the fixed length of a room code.
const codeLength = 6

// CodeGenerator produces room codes. The default implementation is random;
// tests inject a deterministic generator to make collisions reproducible.
type CodeGenerator interface {
	// Generate returns a candidate room code.
	Generate() string
}

// RandomCodeGenerator produces random codes from codeAlphabet.
type RandomCodeGenerator struct {
	rng *rand.Rand
}

// NewRandomCodeGenerator returns a generator seeded from src. Pass nil to use
// a non-deterministic source.
func NewRandomCodeGenerator(src rand.Source) *RandomCodeGenerator {
	if src == nil {
		src = rand.NewSource(rand.Int63())
	}
	return &RandomCodeGenerator{rng: rand.New(src)}
}

// Generate returns a random room code.
func (g *RandomCodeGenerator) Generate() string {
	var sb strings.Builder
	sb.Grow(codeLength)
	for i := 0; i < codeLength; i++ {
		sb.WriteByte(codeAlphabet[g.rng.Intn(len(codeAlphabet))])
	}
	return sb.String()
}

// newToken returns a random opaque token used as a track ID.
func newToken() string {
	const tokenAlphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	var sb strings.Builder
	sb.Grow(32)
	rng := rand.New(rand.NewSource(rand.Int63()))
	for i := 0; i < 32; i++ {
		sb.WriteByte(tokenAlphabet[rng.Intn(len(tokenAlphabet))])
	}
	return sb.String()
}
