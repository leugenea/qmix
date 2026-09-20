package roomcreate

import (
	"net/http"
	"net/http/httptest"
	"net/netip"
	"testing"
)

func identityRequest(remote, xff, forwarded, realIP string) *http.Request {
	req := httptest.NewRequest("POST", "/rooms", nil)
	req.RemoteAddr = remote
	if xff != "" {
		req.Header.Set("X-Forwarded-For", xff)
	}
	if forwarded != "" {
		req.Header.Set("Forwarded", forwarded)
	}
	if realIP != "" {
		req.Header.Set("X-Real-IP", realIP)
	}
	return req
}

func TestIdentityDefaultsToCanonicalImmediatePeerAndIgnoresForwarding(t *testing.T) {
	resolver := NewIdentityResolver(nil)
	for _, tc := range []struct {
		name, remote, want string
	}{
		{"IPv4 host port", "192.0.2.10:4321", "192.0.2.10"},
		{"IPv6 host port", "[2001:db8::10]:4321", "2001:db8::10"},
		{"IPv4 address", "192.0.2.11", "192.0.2.11"},
		{"malformed peer", "not an address", unknownIdentity},
	} {
		t.Run(tc.name, func(t *testing.T) {
			req := identityRequest(tc.remote, "203.0.113.7", "for=203.0.113.8", "203.0.113.9")
			if got := resolver.Identity(req); got != tc.want {
				t.Fatalf("identity = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestIdentityTrustedProxyWalksXFFRightToLeft(t *testing.T) {
	resolver := NewIdentityResolver([]netip.Prefix{
		netip.MustParsePrefix("10.0.0.0/8"),
		netip.MustParsePrefix("2001:db8:1::/48"),
	})
	for _, tc := range []struct {
		name, remote, xff, want string
	}{
		{"one proxy", "10.0.0.4:443", "198.51.100.8", "198.51.100.8"},
		{"trusted multi hop", "10.0.0.4:443", "198.51.100.8, 10.1.2.3", "198.51.100.8"},
		{"IPv6 multi hop", "[2001:db8:1::4]:443", "2001:db8:2::8, 2001:db8:1::3", "2001:db8:2::8"},
		{"all hops trusted uses leftmost", "10.0.0.4:443", "10.2.0.1, 10.1.0.1", "10.2.0.1"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := resolver.Identity(identityRequest(tc.remote, tc.xff, "", "")); got != tc.want {
				t.Fatalf("identity = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestIdentityTrustedProxyFallsBackOnMalformedOrAmbiguousXFF(t *testing.T) {
	resolver := NewIdentityResolver([]netip.Prefix{netip.MustParsePrefix("10.0.0.0/8")})
	for _, xff := range []string{"bad", "198.51.100.1,,10.0.0.2", "198.51.100.1:80", "unknown", "198.51.100.1, bad"} {
		req := identityRequest("10.0.0.4:443", xff, "for=203.0.113.8", "203.0.113.9")
		if got := resolver.Identity(req); got != "10.0.0.4" {
			t.Fatalf("XFF %q identity = %q, want immediate peer fallback", xff, got)
		}
	}

	req := identityRequest("10.0.0.4:443", "198.51.100.1", "", "")
	req.Header.Add("X-Forwarded-For", "198.51.100.2")
	if got := resolver.Identity(req); got != "10.0.0.4" {
		t.Fatalf("multiple XFF fields identity = %q, want immediate peer fallback", got)
	}
}

func TestIdentityNeverUsesForwardedOrXRealIP(t *testing.T) {
	resolver := NewIdentityResolver([]netip.Prefix{netip.MustParsePrefix("10.0.0.0/8")})
	req := identityRequest("10.0.0.4:443", "", "for=198.51.100.1", "198.51.100.2")
	if got := resolver.Identity(req); got != "10.0.0.4" {
		t.Fatalf("identity = %q, want trusted immediate peer when XFF absent", got)
	}
}

func TestIdentityCanonicalizesMappedPeerAndXFFAddresses(t *testing.T) {
	resolver := NewIdentityResolver([]netip.Prefix{netip.MustParsePrefix("::ffff:192.0.2.0/120")})
	for _, tc := range []struct {
		name, remote, xff, want string
	}{
		{name: "mapped transport peer", remote: "[::ffff:192.0.2.4]:443", xff: "198.51.100.8", want: "198.51.100.8"},
		{name: "mapped XFF client", remote: "192.0.2.4:443", xff: "::ffff:198.51.100.8", want: "198.51.100.8"},
		{name: "mapped trusted XFF hop", remote: "192.0.2.4:443", xff: "203.0.113.9, ::ffff:192.0.2.8", want: "203.0.113.9"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got := resolver.Identity(identityRequest(tc.remote, tc.xff, "", "")); got != tc.want {
				t.Fatalf("identity = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestIdentityMappedTrustedPrefixBoundaries(t *testing.T) {
	tests := []struct {
		name   string
		prefix string
		remote string
		xff    string
		want   string
	}{
		{
			name:   "slash 96 trusts every unmapped IPv4 peer",
			prefix: "::ffff:192.0.2.129/96",
			remote: "203.0.113.9:443",
			xff:    "198.51.100.8",
			want:   "198.51.100.8",
		},
		{
			name:   "slash 96 does not trust an IPv6 peer",
			prefix: "::ffff:192.0.2.129/96",
			remote: "[2001:db8::9]:443",
			xff:    "198.51.100.8",
			want:   "2001:db8::9",
		},
		{
			name:   "slash 128 trusts only the exact unmapped IPv4 peer",
			prefix: "::ffff:192.0.2.129/128",
			remote: "192.0.2.129:443",
			xff:    "198.51.100.8",
			want:   "198.51.100.8",
		},
		{
			name:   "slash 128 rejects adjacent unmapped IPv4 peer",
			prefix: "::ffff:192.0.2.129/128",
			remote: "192.0.2.128:443",
			xff:    "198.51.100.8",
			want:   "192.0.2.128",
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			resolver := NewIdentityResolver([]netip.Prefix{netip.MustParsePrefix(tc.prefix)})
			if got := resolver.Identity(identityRequest(tc.remote, tc.xff, "", "")); got != tc.want {
				t.Fatalf("identity = %q, want %q", got, tc.want)
			}
		})
	}
}

func TestIdentityWalksMixedMappedAndUnmappedTrustedXFFHops(t *testing.T) {
	resolver := NewIdentityResolver([]netip.Prefix{
		netip.MustParsePrefix("::ffff:192.0.2.129/120"),
		netip.MustParsePrefix("2001:db8:1::9/48"),
	})
	req := identityRequest(
		"[::ffff:192.0.2.200]:443",
		"::ffff:198.51.100.8, 2001:db8:1::4, ::ffff:192.0.2.9",
		"",
		"",
	)
	if got := resolver.Identity(req); got != "198.51.100.8" {
		t.Fatalf("identity = %q, want canonical first untrusted mixed-chain address", got)
	}
}

func TestIdentityDirectConstructionDropsEveryInvalidTrustedPrefix(t *testing.T) {
	resolver := NewIdentityResolver([]netip.Prefix{
		{},
		netip.MustParsePrefix("::ffff:192.0.2.129/95"),
	})
	if got := resolver.Identity(identityRequest("192.0.2.129:443", "198.51.100.8", "", "")); got != "192.0.2.129" {
		t.Fatalf("identity = %q, want untrusted peer after invalid prefixes were dropped", got)
	}
}

func TestIdentityIgnoresUnsafeMappedTrustedPrefix(t *testing.T) {
	resolver := NewIdentityResolver([]netip.Prefix{netip.MustParsePrefix("::ffff:192.0.2.0/80")})
	if got := resolver.Identity(identityRequest("192.0.2.4:443", "198.51.100.8", "", "")); got != "192.0.2.4" {
		t.Fatalf("identity = %q, want untrusted immediate peer", got)
	}
}
