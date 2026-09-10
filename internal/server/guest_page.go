package server

import (
	_ "embed"
	"net/http"
)

//go:embed guest/index.html
var guestPage []byte

//go:embed guest/app.js
var guestScript []byte

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
