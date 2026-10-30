package server

import (
	"crypto/subtle"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"sort"

	"jhatpat/internal/auth"
	"jhatpat/internal/events"
	"jhatpat/internal/items"
)

// host guards laptop-owner routes: loopback only, plus the host cookie.
func (s *Server) host(h http.HandlerFunc) http.HandlerFunc {
	return s.sameOrigin(func(w http.ResponseWriter, r *http.Request) {
		if !isLoopback(r) || subtle.ConstantTimeCompare([]byte(cookie(r, cookieHost)), []byte(s.hostToken)) != 1 {
			writeErr(w, http.StatusForbidden, "host only")
			return
		}
		h(w, r)
	})
}

func (s *Server) handleHostPage(w http.ResponseWriter, r *http.Request) {
	if !isLoopback(r) {
		http.Error(w, "The host page only opens on the laptop itself.", http.StatusForbidden)
		return
	}
	t := r.URL.Query().Get("t")
	hasCookie := subtle.ConstantTimeCompare([]byte(cookie(r, cookieHost)), []byte(s.hostToken)) == 1
	if subtle.ConstantTimeCompare([]byte(t), []byte(s.hostToken)) != 1 && !hasCookie {
		http.Error(w, "Open JhatPat from the link it printed when it started.", http.StatusForbidden)
		return
	}
	setCookie(w, cookieHost, s.hostToken, 0)
	s.page(w, "host.html")
}

func (s *Server) devicesChanged() {
	s.hub.Send(items.Host, events.Event{Type: "devices", Data: map[string]bool{"changed": true}})
}

func (s *Server) hostState() map[string]any {
	devs := []map[string]any{}
	for _, d := range s.devices.List() {
		devs = append(devs, map[string]any{"id": d.ID, "name": d.Name, "state": d.State.String(), "pid": d.PersistentID})
	}
	known := []map[string]string{}
	for pid, name := range s.groups.Names() {
		known = append(known, map[string]string{"pid": pid, "name": name})
	}
	sort.Slice(known, func(i, j int) bool { return known[i]["name"] < known[j]["name"] })
	return map[string]any{
		"pin": s.join.PIN(), "joinURL": s.joinURL(), "saveDir": s.cfg.SaveDir,
		"devices": devs, "groups": s.groups.List(), "known": known,
		"items": s.views(s.items.All()),
	}
}

func (s *Server) hostRoutes() {
	m := s.mux
	m.HandleFunc("GET /api/host/state", s.host(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, s.hostState())
	}))
	m.HandleFunc("POST /api/host/pin/regenerate", s.host(func(w http.ResponseWriter, r *http.Request) {
		s.join.Regenerate()
		s.hub.Send(items.Host, events.Event{Type: "pin", Data: map[string]string{"pin": s.join.PIN()}})
		writeJSON(w, http.StatusOK, map[string]string{"pin": s.join.PIN(), "joinURL": s.joinURL()})
	}))

	deviceAction := func(act func(id string) error, after func(d *auth.Device)) http.HandlerFunc {
		return s.host(func(w http.ResponseWriter, r *http.Request) {
			id := r.PathValue("id")
			d, ok := s.devices.Get(id)
			if !ok {
				writeErr(w, http.StatusNotFound, "no device")
				return
			}
			if err := act(id); err != nil {
				writeErr(w, http.StatusBadRequest, err.Error())
				return
			}
			if after != nil {
				after(d)
			}
			s.devicesChanged()
			writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
		})
	}
	m.HandleFunc("POST /api/host/devices/{id}/approve", deviceAction(s.devices.Approve, func(d *auth.Device) {
		_ = s.groups.RememberName(d.PersistentID, d.Name)
	}))
	m.HandleFunc("POST /api/host/devices/{id}/block", deviceAction(s.devices.Block, func(d *auth.Device) {
		s.hub.Send(d.ID, events.Event{Type: "kicked", Data: map[string]bool{"blocked": true}})
	}))
	m.HandleFunc("POST /api/host/devices/{id}/kick", deviceAction(s.devices.Kick, func(d *auth.Device) {
		s.hub.Send(d.ID, events.Event{Type: "kicked", Data: map[string]bool{"blocked": false}})
	}))
	m.HandleFunc("POST /api/host/devices/{id}/rename", s.host(func(w http.ResponseWriter, r *http.Request) {
		var req struct{ Name string }
		if !readJSON(w, r, 4096, &req) {
			return
		}
		id := r.PathValue("id")
		if err := s.devices.Rename(id, req.Name); err != nil {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
		if d, ok := s.devices.Get(id); ok && d.State == auth.Approved {
			_ = s.groups.RememberName(d.PersistentID, d.Name)
		}
		s.devicesChanged()
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	}))

	m.HandleFunc("POST /api/host/groups", s.host(func(w http.ResponseWriter, r *http.Request) {
		var req struct{ Name string }
		if !readJSON(w, r, 4096, &req) {
			return
		}
		g, err := s.groups.Create(req.Name)
		if err != nil {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
		s.devicesChanged()
		writeJSON(w, http.StatusOK, g)
	}))
	m.HandleFunc("PATCH /api/host/groups/{id}", s.host(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Name    *string
			Members *[]string
		}
		if !readJSON(w, r, 65536, &req) {
			return
		}
		id := r.PathValue("id")
		if req.Name != nil {
			if err := s.groups.Rename(id, *req.Name); err != nil {
				writeErr(w, http.StatusBadRequest, err.Error())
				return
			}
		}
		if req.Members != nil {
			if err := s.groups.SetMembers(id, *req.Members); err != nil {
				writeErr(w, http.StatusBadRequest, err.Error())
				return
			}
		}
		s.devicesChanged()
		g, _ := s.groups.Get(id)
		writeJSON(w, http.StatusOK, g)
	}))
	m.HandleFunc("DELETE /api/host/groups/{id}", s.host(func(w http.ResponseWriter, r *http.Request) {
		if err := s.groups.Delete(r.PathValue("id")); err != nil {
			writeErr(w, http.StatusNotFound, err.Error())
			return
		}
		s.devicesChanged()
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	}))

	m.HandleFunc("POST /api/host/send", s.host(s.handleHostSend))
	m.HandleFunc("DELETE /api/host/items/{id}", s.host(func(w http.ResponseWriter, r *http.Request) {
		it, ok := s.items.GetFor(items.Host, r.PathValue("id"))
		if !ok { // host may remove any item, even phone-to-phone relays
			for _, x := range s.items.All() {
				if x.ID == r.PathValue("id") {
					it, ok = x, true
				}
			}
		}
		if !ok {
			writeErr(w, http.StatusNotFound, "no item")
			return
		}
		s.items.Remove(it.ID)
		for _, a := range append([]string{it.From, items.Host}, it.To...) {
			s.hub.Send(a, events.Event{Type: "item-removed", Data: map[string]string{"id": it.ID}})
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	}))
	m.HandleFunc("POST /api/host/open-folder", s.host(func(w http.ResponseWriter, r *http.Request) {
		if s.cfg.OpenPath == nil {
			writeErr(w, http.StatusNotImplemented, "not available")
			return
		}
		if err := s.cfg.OpenPath(s.cfg.SaveDir); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	}))
	m.HandleFunc("GET /api/host/events", s.host(func(w http.ResponseWriter, r *http.Request) {
		ch, stop := s.hub.Subscribe(items.Host)
		defer stop()
		events.ServeSSE(w, r, ch)
	}))
	s.uploadRoutes("/api/host/uploads", s.outbox, func(h ownerHandler) http.HandlerFunc {
		return s.host(func(w http.ResponseWriter, r *http.Request) { h(w, r, items.Host) })
	})
}

func (s *Server) handleHostSend(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Paths []string
		Text  string
		To    Target
	}
	if !readJSON(w, r, MaxText+65536, &req) {
		return
	}
	if len(req.Text) > MaxText {
		writeErr(w, http.StatusRequestEntityTooLarge, "too large")
		return
	}
	if req.Text == "" && len(req.Paths) == 0 {
		writeErr(w, http.StatusBadRequest, "nothing to send")
		return
	}
	to, err := s.resolveTo(items.Host, req.To)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	var pending []items.Item
	for _, p := range req.Paths {
		abs, err := filepath.Abs(p)
		if err != nil {
			writeErr(w, http.StatusBadRequest, "bad path")
			return
		}
		info, err := os.Stat(abs)
		if errors.Is(err, os.ErrNotExist) || err != nil {
			writeErr(w, http.StatusBadRequest, "not found: "+p)
			return
		}
		it := items.Item{Kind: items.File, Name: filepath.Base(abs), Path: abs, Size: info.Size(), From: items.Host, To: to}
		if info.IsDir() {
			it.Kind, it.Size = items.Folder, 0
		}
		pending = append(pending, it)
	}
	if req.Text != "" {
		pending = append(pending, items.Item{Kind: items.Text, Name: "Text", Text: req.Text, Size: int64(len(req.Text)), From: items.Host, To: to})
	}
	out := []map[string]any{}
	for _, it := range pending {
		out = append(out, s.view(s.addItem(it)))
	}
	writeJSON(w, http.StatusOK, out)
}
