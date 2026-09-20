package roomcreate

import (
	"net"
	"net/http"
	"net/netip"
	"strings"
)

const unknownIdentity = "unknown-peer"

// IdentityResolver derives a bounded-registry key from the transport peer and
// an explicitly trusted X-Forwarded-For chain.
type IdentityResolver struct {
	trusted []netip.Prefix
}

// NewIdentityResolver canonicalizes representable IPv4-mapped prefixes and
// ignores non-representable mapped prefixes. Startup configuration rejects the
// latter; ignoring them here keeps direct construction fail-closed.
func NewIdentityResolver(trusted []netip.Prefix) *IdentityResolver {
	canonical := make([]netip.Prefix, 0, len(trusted))
	for _, prefix := range trusted {
		if normalized, ok := CanonicalTrustedPrefix(prefix); ok {
			canonical = append(canonical, normalized)
		}
	}
	return &IdentityResolver{trusted: canonical}
}

// CanonicalTrustedPrefix returns the address-family representation used by
// Identity. IPv4-mapped prefixes covering only mapped addresses (bits >= 96)
// become ordinary IPv4 prefixes; broader mapped forms are not representable
// after candidate addresses are unmapped and are rejected.
func CanonicalTrustedPrefix(prefix netip.Prefix) (netip.Prefix, bool) {
	if !prefix.IsValid() {
		return netip.Prefix{}, false
	}
	if !prefix.Addr().Is4In6() {
		return prefix.Masked(), true
	}
	if prefix.Bits() < 96 {
		return netip.Prefix{}, false
	}
	return netip.PrefixFrom(prefix.Addr().Unmap(), prefix.Bits()-96).Masked(), true
}

// Identity ignores forwarding headers unless the immediate transport peer is
// trusted. Only one unambiguous X-Forwarded-For field is supported.
func (r *IdentityResolver) Identity(req *http.Request) string {
	peer, ok := parsePeer(req.RemoteAddr)
	if !ok {
		return unknownIdentity
	}
	fallback := peer.String()
	if !r.isTrusted(peer) {
		return fallback
	}
	values := req.Header.Values("X-Forwarded-For")
	if len(values) == 0 {
		return fallback
	}
	if len(values) != 1 {
		return fallback
	}
	parts := strings.Split(values[0], ",")
	chain := make([]netip.Addr, len(parts))
	for i, part := range parts {
		addr, err := netip.ParseAddr(strings.TrimSpace(part))
		if err != nil || addr.Zone() != "" {
			return fallback
		}
		chain[i] = addr.Unmap()
	}
	for i := len(chain) - 1; i >= 0; i-- {
		if !r.isTrusted(chain[i]) {
			return chain[i].String()
		}
	}
	return chain[0].String()
}

func parsePeer(remote string) (netip.Addr, bool) {
	if addr, err := netip.ParseAddr(strings.Trim(remote, "[]")); err == nil && addr.Zone() == "" {
		return addr.Unmap(), true
	}
	host, _, err := net.SplitHostPort(remote)
	if err != nil {
		return netip.Addr{}, false
	}
	addr, err := netip.ParseAddr(strings.Trim(host, "[]"))
	if err != nil || addr.Zone() != "" {
		return netip.Addr{}, false
	}
	return addr.Unmap(), true
}

func (r *IdentityResolver) isTrusted(addr netip.Addr) bool {
	addr = addr.Unmap()
	for _, prefix := range r.trusted {
		if prefix.Contains(addr) {
			return true
		}
	}
	return false
}
