package main

import (
	"testing"
)

func TestListenAddrDefault(t *testing.T) {
	t.Setenv("QMIX_ADDR", "")
	if got := listenAddr(); got != ":8080" {
		t.Fatalf("addr = %q, want :8080", got)
	}
}

func TestListenAddrFromEnv(t *testing.T) {
	t.Setenv("QMIX_ADDR", ":9000")
	if got := listenAddr(); got != ":9000" {
		t.Fatalf("addr = %q, want :9000", got)
	}
}
