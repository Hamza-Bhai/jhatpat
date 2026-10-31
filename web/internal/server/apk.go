package server

import (
	"net/http"
	"os"
	"time"
)

const (
	apkDefaultMinutes = 10
	apkMaxMinutes     = 60
)

// apkAvailable reports whether the host has an APK to share (the app passes
// its own installed APK with -apk).
func (s *Server) apkAvailable() bool {
	if s.cfg.APKPath == "" {
		return false
	}
	fi, err := os.Stat(s.cfg.APKPath)
	return err == nil && fi.Mode().IsRegular()
}

// apkActiveUntil returns when sharing ends, and false when it is off or over.
func (s *Server) apkActiveUntil() (time.Time, bool) {
	ns := s.apkUntil.Load()
	if ns == 0 {
		return time.Time{}, false
	}
	until := time.Unix(0, ns)
	if !s.now().Before(until) {
		return time.Time{}, false
	}
	return until, true
}

// apkState is the "apk" field of the host state.
func (s *Server) apkState() map[string]any {
	until := ""
	if t, ok := s.apkActiveUntil(); ok {
		until = t.UTC().Format(time.RFC3339)
	}
	return map[string]any{"available": s.apkAvailable(), "enabledUntil": until}
}

func (s *Server) apkRoutes() {
	m := s.mux
	m.HandleFunc("POST /api/host/apk/enable", s.host(func(w http.ResponseWriter, r *http.Request) {
		var req struct{ Minutes int }
		if !readJSON(w, r, 1024, &req) {
			return
		}
		if req.Minutes == 0 {
			req.Minutes = apkDefaultMinutes
		}
		if req.Minutes < 1 || req.Minutes > apkMaxMinutes {
			writeErr(w, http.StatusBadRequest, "minutes must be 1 to 60")
			return
		}
		if !s.apkAvailable() {
			writeErr(w, http.StatusNotFound, "no APK to share")
			return
		}
		until := s.now().Add(time.Duration(req.Minutes) * time.Minute)
		s.apkUntil.Store(until.UnixNano())
		writeJSON(w, http.StatusOK, map[string]string{"enabledUntil": until.UTC().Format(time.RFC3339)})
	}))
	m.HandleFunc("POST /api/host/apk/disable", s.host(func(w http.ResponseWriter, r *http.Request) {
		s.apkUntil.Store(0)
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	}))
	m.HandleFunc("GET /app.apk", s.handleAPK)
}

// handleAPK serves the app to anyone on the network, but only while the host
// has switched sharing on. The APK is the public app, not a secret.
func (s *Server) handleAPK(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.apkActiveUntil(); !ok || !s.apkAvailable() {
		http.NotFound(w, r)
		return
	}
	f, err := os.Open(s.cfg.APKPath)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	fi, err := f.Stat()
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="JhatPat.apk"`)
	w.Header().Set("Cache-Control", "no-store")
	http.ServeContent(w, r, "JhatPat.apk", fi.ModTime(), f)
}
