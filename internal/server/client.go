package server

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/Hamza-Bhai/jhatpat/internal/auth"
	"github.com/Hamza-Bhai/jhatpat/internal/events"
	"github.com/Hamza-Bhai/jhatpat/internal/items"
)

// ---- joining ----

// guessName turns a User-Agent into a friendly default device name.
func guessName(ua string) string {
	switch l := strings.ToLower(ua); {
	case strings.Contains(l, "iphone"):
		return "iPhone"
	case strings.Contains(l, "ipad"):
		return "iPad"
	case strings.Contains(l, "android"):
		return "Android Phone"
	case strings.Contains(l, "windows"):
		return "Windows PC"
	case strings.Contains(l, "mac os"):
		return "Mac"
	case strings.Contains(l, "linux"):
		return "Linux PC"
	}
	return "Device"
}

// persistentID returns the browser's long-lived id cookie, creating it if needed.
func persistentID(w http.ResponseWriter, r *http.Request) string {
	if pid := cookie(r, cookiePID); pid != "" && len(pid) <= 64 {
		return pid
	}
	pid := auth.RandomID(16)
	setCookie(w, cookiePID, pid, 365*24*time.Hour)
	return pid
}

func (s *Server) newPending(w http.ResponseWriter, r *http.Request, pid, name string) *auth.Device {
	// The join page pre-fills a guess like "Android Phone"; a name this device
	// chose before beats that guess, but anything the user typed wins.
	guess := guessName(r.UserAgent())
	if n := strings.TrimSpace(name); n == "" || n == guess {
		if known, ok := s.groups.KnownName(pid); ok {
			name = known
		} else {
			name = guess
		}
	}
	if !isLoopback(r) {
		s.phoneSeen.Store(true)
	}
	d := s.devices.Request(name, pid)
	setCookie(w, cookieDev, d.Token, 0)
	s.hub.Send(items.Host, events.Event{Type: "device-request", Data: map[string]string{"id": d.ID, "name": d.Name}})
	return d
}

func (s *Server) handleRoot(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.devices.ByToken(cookie(r, cookieDev)); ok {
		s.page(w, "client.html")
		return
	}
	persistentID(w, r)
	s.page(w, "join.html")
}

func (s *Server) handleSecretJoin(w http.ResponseWriter, r *http.Request) {
	if !s.join.CheckSecret(r.PathValue("secret")) {
		http.Error(w, "This QR code is old. Scan the new one on the laptop.", http.StatusNotFound)
		return
	}
	pid := persistentID(w, r)
	if s.devices.IsBlocked(pid) {
		http.Error(w, "Blocked by the laptop owner.", http.StatusForbidden)
		return
	}
	if d, ok := s.devices.Lookup(cookie(r, cookieDev)); !ok || d.State == auth.Blocked {
		s.newPending(w, r, pid, "")
	}
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (s *Server) handlePINJoin(w http.ResponseWriter, r *http.Request) {
	var req struct{ PIN, Name string }
	if !readJSON(w, r, 4096, &req) {
		return
	}
	pid := persistentID(w, r)
	if s.devices.IsBlocked(pid) {
		writeErr(w, http.StatusForbidden, "blocked")
		return
	}
	switch err := s.join.CheckPIN(remoteIP(r).String(), strings.TrimSpace(req.PIN)); {
	case errors.Is(err, auth.ErrWrongPIN):
		writeErr(w, http.StatusUnauthorized, "wrong-pin")
		return
	case errors.Is(err, auth.ErrIPLocked), errors.Is(err, auth.ErrGlobalLocked):
		writeErr(w, http.StatusTooManyRequests, "locked")
		return
	case err != nil:
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	d := s.newPending(w, r, pid, req.Name)
	writeJSON(w, http.StatusOK, map[string]string{"id": d.ID, "state": d.State.String()})
}

func (s *Server) handleJoinStatus(w http.ResponseWriter, r *http.Request) {
	d, ok := s.devices.Lookup(cookie(r, cookieDev))
	if !ok {
		writeJSON(w, http.StatusOK, map[string]string{"state": "none"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"state": d.State.String(), "name": d.Name})
}

// ---- approved devices ----

func (s *Server) dev(h func(http.ResponseWriter, *http.Request, *auth.Device)) http.HandlerFunc {
	return s.sameOrigin(func(w http.ResponseWriter, r *http.Request) {
		d, ok := s.devices.ByToken(cookie(r, cookieDev))
		if !ok {
			writeErr(w, http.StatusUnauthorized, "not-approved")
			return
		}
		h(w, r, d)
	})
}

func (s *Server) deviceRoutes() {
	m := s.mux
	m.HandleFunc("GET /api/dev/me", s.dev(func(w http.ResponseWriter, r *http.Request, d *auth.Device) {
		writeJSON(w, http.StatusOK, map[string]string{"id": d.ID, "name": d.Name})
	}))
	m.HandleFunc("POST /api/dev/rename", s.dev(func(w http.ResponseWriter, r *http.Request, d *auth.Device) {
		var req struct{ Name string }
		if !readJSON(w, r, 4096, &req) {
			return
		}
		if err := s.devices.Rename(d.ID, req.Name); err != nil {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
		nd, _ := s.devices.Get(d.ID)
		_ = s.groups.RememberName(nd.PersistentID, nd.Name)
		s.devicesChanged()
		writeJSON(w, http.StatusOK, map[string]string{"id": nd.ID, "name": nd.Name})
	}))
	m.HandleFunc("GET /api/dev/items", s.dev(func(w http.ResponseWriter, r *http.Request, d *auth.Device) {
		writeJSON(w, http.StatusOK, s.views(s.items.Visible(d.ID)))
	}))
	m.HandleFunc("GET /api/dev/items/{id}/download", s.dev(func(w http.ResponseWriter, r *http.Request, d *auth.Device) {
		s.serveVisible(w, r, d.ID, false)
	}))
	m.HandleFunc("GET /api/dev/items/{id}/view", s.dev(func(w http.ResponseWriter, r *http.Request, d *auth.Device) {
		s.serveVisible(w, r, d.ID, true)
	}))
	m.HandleFunc("GET /api/dev/targets", s.dev(func(w http.ResponseWriter, r *http.Request, d *auth.Device) {
		devs := []map[string]string{}
		for _, o := range s.devices.List() {
			if o.State == auth.Approved && o.ID != d.ID {
				devs = append(devs, map[string]string{"id": o.ID, "name": o.Name})
			}
		}
		grps := []map[string]string{}
		for _, g := range s.groups.List() {
			grps = append(grps, map[string]string{"id": g.ID, "name": g.Name})
		}
		writeJSON(w, http.StatusOK, map[string]any{"devices": devs, "groups": grps})
	}))
	m.HandleFunc("POST /api/dev/text", s.dev(func(w http.ResponseWriter, r *http.Request, d *auth.Device) {
		var req struct {
			Text string
			To   Target
		}
		if !readJSON(w, r, MaxText+4096, &req) {
			return
		}
		if len(req.Text) > MaxText {
			writeErr(w, http.StatusRequestEntityTooLarge, "too large")
			return
		}
		to, err := s.resolveTo(d.ID, req.To)
		if err != nil {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
		it := s.addItem(items.Item{Kind: items.Text, Name: "Text", Text: req.Text, Size: int64(len(req.Text)), From: d.ID, To: to})
		writeJSON(w, http.StatusOK, s.view(it))
	}))
	s.uploadRoutes("/api/dev/uploads", s.uploads, func(h ownerHandler) http.HandlerFunc {
		return s.dev(func(w http.ResponseWriter, r *http.Request, d *auth.Device) { h(w, r, d.ID) })
	})
	m.HandleFunc("GET /api/dev/events", s.dev(func(w http.ResponseWriter, r *http.Request, d *auth.Device) {
		ch, stop := s.hub.Subscribe(d.ID)
		defer stop()
		events.ServeSSE(w, r, ch)
	}))
}
