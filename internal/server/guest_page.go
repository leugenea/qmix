package server

import (
	_ "embed"
	"net/http"
)

//go:embed guest/index.html
var guestPage []byte

//go:embed guest/app.js
var guestScript []byte

//go:embed guest/manifest.webmanifest
var guestManifest []byte

//go:embed guest/qmix-192.svg
var guestIcon192 []byte

//go:embed guest/qmix-512.svg
var guestIcon512 []byte

// handleGuestPage serves the public room UI after checking that the room exists.
func (s *Server) handleGuestPage(w http.ResponseWriter, r *http.Request) {
	if s.store.Get(r.PathValue("code")) == nil {
		writeError(w, http.StatusNotFound, "room not found")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	_, _ = w.Write(guestPage)
}

func handleGuestScript(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	_, _ = w.Write(guestScript)
}

func handleGuestManifest(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/manifest+json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	_, _ = w.Write(guestManifest)
}

func handleGuestIcon(icon []byte) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "image/svg+xml; charset=utf-8")
		w.Header().Set("Cache-Control", "public, max-age=86400")
		_, _ = w.Write(icon)
	}
}
