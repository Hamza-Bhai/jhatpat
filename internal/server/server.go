// Package server wires JhatPat's HTTP API and web pages together.
package server

import (
	"context"
	"encoding/json"
	"errors"
	"io/fs"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"jhatpat/internal/auth"
	"jhatpat/internal/events"
	"jhatpat/internal/groups"
	"jhatpat/internal/items"
	"jhatpat/internal/netdoctor"
	"jhatpat/internal/transfer"
)

// MaxText is the largest text item accepted.
const MaxText = 1 << 20

const (
	cookieDev  = "jp_dev"
	cookiePID  = "jp_pid"
	cookieHost = "jp_host"
)

// Config holds the runtime settings for a Server.
type Config struct {
	Port      int
	SaveDir   string
	ConfigDir string
	// Web holds the page assets (host.html, client.html, join.html, static/…).
	// Nil serves minimal placeholder pages.
	Web fs.FS
	// OpenPath opens a folder in the OS file manager. Nil disables it.
	OpenPath func(path string) error
	// Doctor runs network checks. Nil disables the doctor endpoints.
	Doctor DoctorRunner
}

// DoctorRunner is what the host page needs from the Network Doctor.
type DoctorRunner interface {
	Run(ctx context.Context, opt netdoctor.Options) netdoctor.Report
	FixFirewall() error
}

// Server is the JhatPat HTTP server.
type Server struct {
	cfg        Config
	mux        *http.ServeMux
	hostToken  string
	join       *auth.Join
	devices    *auth.Registry
	groups     *groups.Store
	items      *items.Store
	hub        *events.Hub
	uploads    *transfer.Uploads // phone → laptop, saved in SaveDir
	outbox     *transfer.Uploads // host drag-and-drop staging
	joinURL    func() string
	uploadMeta sync.Map    // upload id → uploadMeta
	phoneSeen  atomic.Bool // a non-loopback device joined this run
}

// New builds a Server from cfg.
func New(cfg Config) (*Server, error) {
	if cfg.SaveDir == "" || cfg.ConfigDir == "" {
		return nil, errors.New("SaveDir and ConfigDir are required")
	}
	if err := os.MkdirAll(cfg.SaveDir, 0o755); err != nil {
		return nil, err
	}
	outboxDir := filepath.Join(cfg.ConfigDir, "outbox")
	_ = os.RemoveAll(outboxDir)
	if err := os.MkdirAll(outboxDir, 0o755); err != nil {
		return nil, err
	}
	g, err := groups.Open(cfg.ConfigDir)
	if err != nil {
		return nil, err
	}
	s := &Server{
		cfg:       cfg,
		mux:       http.NewServeMux(),
		hostToken: auth.RandomID(24),
		join:      auth.NewJoin(nil),
		devices:   auth.NewRegistry(),
		groups:    g,
		items:     items.NewStore(),
		hub:       events.NewHub(),
		uploads:   transfer.NewUploads(cfg.SaveDir),
		outbox:    transfer.NewUploads(outboxDir),
	}
	s.joinURL = func() string { return "" }
	s.routes()
	return s, nil
}

// Handler returns the root HTTP handler.
func (s *Server) Handler() http.Handler { return s.mux }

// HostToken is the secret the host page URL carries.
func (s *Server) HostToken() string { return s.hostToken }

// Join exposes the PIN/secret holder (for the startup banner).
func (s *Server) Join() *auth.Join { return s.join }

// SetJoinURL sets how the server builds the QR join link (it depends on the chosen IP).
func (s *Server) SetJoinURL(f func() string) { s.joinURL = f }

// Hub exposes the event hub (Network Doctor publishes through it).
func (s *Server) Hub() *events.Hub { return s.hub }

// PhoneSeen reports whether a device on the network has reached JhatPat this run.
func (s *Server) PhoneSeen() bool { return s.phoneSeen.Load() }

// Shutdown removes unfinished partial uploads.
func (s *Server) Shutdown() {
	s.uploads.AbortAll()
	s.outbox.AbortAll()
}

func (s *Server) routes() {
	m := s.mux
	m.HandleFunc("GET /api/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "name": "JhatPat"})
	})

	// Joining (anyone on the LAN).
	m.HandleFunc("GET /{$}", s.handleRoot)
	m.HandleFunc("GET /j/{secret}", s.handleSecretJoin)
	m.HandleFunc("POST /api/join", s.sameOrigin(s.handlePINJoin))
	m.HandleFunc("GET /api/join/status", s.handleJoinStatus)

	// Host (laptop owner).
	m.HandleFunc("GET /host", s.handleHostPage)
	s.hostRoutes()

	// Approved devices.
	s.deviceRoutes()

	if s.cfg.Web != nil {
		if static, err := fs.Sub(s.cfg.Web, "static"); err == nil {
			m.Handle("GET /static/", http.StripPrefix("/static/", http.FileServerFS(static)))
		}
	}
}

// ---- helpers ----

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func readJSON(w http.ResponseWriter, r *http.Request, limit int64, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, limit)
	if err := json.NewDecoder(r.Body).Decode(v); err != nil {
		var tooBig *http.MaxBytesError
		if errors.As(err, &tooBig) {
			writeErr(w, http.StatusRequestEntityTooLarge, "too large")
		} else {
			writeErr(w, http.StatusBadRequest, "bad request")
		}
		return false
	}
	return true
}

func remoteIP(r *http.Request) net.IP {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	return net.ParseIP(host)
}

func isLoopback(r *http.Request) bool {
	ip := remoteIP(r)
	return ip != nil && ip.IsLoopback()
}

// sameOrigin rejects state-changing requests whose Origin header points at
// another site (browsers always send Origin on cross-site POSTs).
func (s *Server) sameOrigin(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if o := r.Header.Get("Origin"); o != "" {
			u, err := url.Parse(o)
			if err != nil || u.Host != r.Host {
				writeErr(w, http.StatusForbidden, "cross-origin request")
				return
			}
		}
		h(w, r)
	}
}

func setCookie(w http.ResponseWriter, name, value string, maxAge time.Duration) {
	http.SetCookie(w, &http.Cookie{
		Name: name, Value: value, Path: "/", HttpOnly: true,
		SameSite: http.SameSiteStrictMode, MaxAge: int(maxAge.Seconds()),
	})
}

func cookie(r *http.Request, name string) string {
	c, err := r.Cookie(name)
	if err != nil {
		return ""
	}
	return c.Value
}

// page serves one HTML page from Web, or a placeholder.
func (s *Server) page(w http.ResponseWriter, name string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	if s.cfg.Web == nil {
		_, _ = w.Write([]byte("<!doctype html><title>JhatPat</title><p>" + name))
		return
	}
	b, err := fs.ReadFile(s.cfg.Web, name)
	if err != nil {
		http.Error(w, "missing page", http.StatusInternalServerError)
		return
	}
	_, _ = w.Write(b)
}
