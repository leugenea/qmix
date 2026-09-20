package roomcreate_test

import (
	"testing"
	"time"

	"github.com/leugenea/qmix/internal/roomcreate"
)

func TestLimiterReclaimsEmptyIdentityThroughPublicAPI(t *testing.T) {
	now := time.Unix(100, 0)
	limiter := roomcreate.NewLimiter(60, 1, 1, func() time.Time { return now })
	if denial := limiter.Allow(""); denial != nil {
		t.Fatalf("initial empty identity admission denied: %v", denial)
	}
	now = now.Add(time.Second)
	if denial := limiter.Allow("replacement"); denial != nil {
		t.Fatalf("replacement denied although the empty-identity bucket was fully replenished: %v", denial)
	}
	if denial := limiter.Allow("replacement"); denial == nil {
		t.Fatal("replacement bucket was not retained after reclaiming the empty identity")
	}
}
