package resolver

import (
	"net/http"
	"time"
)

// httpTimeout bounds the whole metadata request: dial, response headers and
// body read (qmix#40). Resolver payloads are small JSON documents or HTML
// pages, so a whole-request timeout is safe and a hung upstream releases the
// request instead of holding it indefinitely.
const httpTimeout = 10 * time.Second

// defaultClient is the fallback HTTP client for resolvers without an
// injected client.
var defaultClient = &http.Client{Timeout: httpTimeout}
