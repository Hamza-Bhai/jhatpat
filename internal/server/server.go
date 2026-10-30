// Package server wires JhatPat's HTTP API and web pages together.
package server

import (
	"encoding/json"
	"net/http"
)

// Config holds the runtime settings for a Server.
type Config struct {
	Port      int
	SaveDir   string
	ConfigDir string
}

// Server is the JhatPat HTTP server.
type Server struct {
	cfg Config
	mux *http.ServeMux
}

// New builds a Server from cfg.
func New(cfg Config) (*Server, error) {
	s := &Server{cfg: cfg, mux: http.NewServeMux()}
	s.routes()
	return s, nil
}

// Handler returns the root HTTP handler.
func (s *Server) Handler() http.Handler { return s.mux }

func (s *Server) routes() {
	s.mux.HandleFunc("GET /api/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "name": "JhatPat"})
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
