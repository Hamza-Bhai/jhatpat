package server

import (
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"jhatpat/internal/auth"
	"jhatpat/internal/events"
	"jhatpat/internal/items"
	"jhatpat/internal/transfer"
)

// Target says who an item goes to.
type Target struct {
	Kind string `json:"kind"` // "host", "device", "group", "all"; "" means host
	ID   string `json:"id"`
}

var errNoRecipients = errors.New("no recipients are connected")

// resolveTo turns a Target into recipient ids for an item sent by sender.
// Only currently approved devices are included.
func (s *Server) resolveTo(sender string, t Target) ([]string, error) {
	approved := map[string]auth.Device{}
	for _, d := range s.devices.List() {
		if d.State == auth.Approved && d.ID != sender {
			approved[d.ID] = d
		}
	}
	var to []string
	switch t.Kind {
	case "", "host":
		if sender == items.Host {
			return nil, errors.New("host cannot send to itself")
		}
		to = []string{items.Host}
	case "device":
		if _, ok := approved[t.ID]; !ok {
			return nil, errors.New("that device is not connected")
		}
		to = []string{t.ID}
	case "group":
		g, ok := s.groups.Get(t.ID)
		if !ok {
			return nil, errors.New("group not found")
		}
		members := map[string]bool{}
		for _, pid := range g.Members {
			members[pid] = true
		}
		for id, d := range approved {
			if members[d.PersistentID] {
				to = append(to, id)
			}
		}
	case "all":
		for id := range approved {
			to = append(to, id)
		}
		if sender != items.Host {
			to = append(to, items.Host)
		}
	default:
		return nil, errors.New("unknown target")
	}
	if len(to) == 0 {
		return nil, errNoRecipients
	}
	return to, nil
}

// addItem stores it and notifies everyone involved.
func (s *Server) addItem(it items.Item) items.Item {
	it = s.items.Add(it)
	ev := events.Event{Type: "item", Data: map[string]string{"id": it.ID}}
	notified := map[string]bool{}
	for _, a := range append([]string{it.From, items.Host}, it.To...) {
		if !notified[a] {
			notified[a] = true
			s.hub.Send(a, ev)
		}
	}
	return it
}

func (s *Server) nameOf(id string) string {
	if id == items.Host {
		return "Laptop"
	}
	if d, ok := s.devices.Get(id); ok {
		return d.Name
	}
	return "?"
}

// view is the JSON shape of an item for pages.
func (s *Server) view(it items.Item) map[string]any {
	names := make([]string, len(it.To))
	for i, id := range it.To {
		names[i] = s.nameOf(id)
	}
	return map[string]any{
		"id": it.ID, "kind": it.Kind, "name": it.Name, "size": it.Size, "text": it.Text,
		"from": it.From, "fromName": s.nameOf(it.From), "to": it.To, "toNames": names,
		"sha256": it.SHA256, "createdAt": it.CreatedAt,
	}
}

func (s *Server) views(list []items.Item) []map[string]any {
	out := make([]map[string]any, len(list))
	for i, it := range list {
		out[i] = s.view(it)
	}
	return out
}

func contentDisposition(name string) string {
	ascii := strings.Map(func(r rune) rune {
		if r < 32 || r > 126 || r == '"' || r == '\\' {
			return '_'
		}
		return r
	}, name)
	return fmt.Sprintf(`attachment; filename="%s"; filename*=UTF-8''%s`, ascii, url.PathEscape(name))
}

// serveItem streams an item to viewer, or 404 if they may not see it.
func (s *Server) serveItem(w http.ResponseWriter, r *http.Request, viewer string) {
	it, ok := s.items.GetFor(viewer, r.PathValue("id"))
	if !ok {
		http.NotFound(w, r)
		return
	}
	switch it.Kind {
	case items.Text:
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = w.Write([]byte(it.Text))
	case items.Folder:
		w.Header().Set("Content-Type", "application/zip")
		w.Header().Set("Content-Disposition", contentDisposition(it.Name+".zip"))
		_ = transfer.WriteZip(w, it.Path)
	default:
		f, err := os.Open(it.Path)
		if err != nil {
			http.Error(w, "file is gone from the laptop", http.StatusGone)
			return
		}
		defer f.Close()
		info, err := f.Stat()
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		w.Header().Set("Content-Disposition", contentDisposition(it.Name))
		http.ServeContent(w, r, it.Name, info.ModTime(), f)
	}
}

// ---- chunked uploads, shared by host (outbox) and devices ----

type ownerHandler func(w http.ResponseWriter, r *http.Request, owner string)

type uploadMeta struct {
	name string
	to   Target
}

func (s *Server) uploadRoutes(prefix string, u *transfer.Uploads, wrap func(ownerHandler) http.HandlerFunc) {
	m := s.mux
	m.HandleFunc("POST "+prefix, wrap(func(w http.ResponseWriter, r *http.Request, owner string) {
		var req struct {
			Name, RelPath string
			Size          int64
			To            Target
		}
		if !readJSON(w, r, 8192, &req) {
			return
		}
		rel := req.RelPath
		if rel == "" {
			rel = req.Name
		}
		if _, err := s.resolveTo(owner, req.To); err != nil {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
		id, err := u.Start(owner, rel, req.Size)
		if err != nil {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
		s.uploadMeta.Store(id, uploadMeta{name: filepath.ToSlash(rel), to: req.To})
		writeJSON(w, http.StatusOK, map[string]any{"id": id, "chunkSize": transfer.ChunkSize})
	}))
	m.HandleFunc("GET "+prefix+"/{id}", wrap(func(w http.ResponseWriter, r *http.Request, owner string) {
		off, err := u.Offset(owner, r.PathValue("id"))
		if err != nil {
			writeErr(w, http.StatusNotFound, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]int64{"offset": off})
	}))
	m.HandleFunc("PUT "+prefix+"/{id}", wrap(func(w http.ResponseWriter, r *http.Request, owner string) {
		offset, err := strconv.ParseInt(r.URL.Query().Get("offset"), 10, 64)
		if err != nil {
			writeErr(w, http.StatusBadRequest, "offset")
			return
		}
		got, err := u.WriteChunk(owner, r.PathValue("id"), offset, r.Body)
		switch {
		case err == nil:
			writeJSON(w, http.StatusOK, map[string]int64{"offset": got})
		case errors.Is(err, transfer.ErrNotFound):
			writeErr(w, http.StatusNotFound, err.Error())
		case errors.Is(err, transfer.ErrOffsetMismatch):
			writeJSON(w, http.StatusConflict, map[string]any{"error": err.Error(), "offset": got})
		case errors.Is(err, transfer.ErrChunkTooLarge), errors.Is(err, transfer.ErrTooMuchData):
			writeErr(w, http.StatusRequestEntityTooLarge, err.Error())
		default: // e.g. disk full
			writeErr(w, http.StatusInternalServerError, "could not save: "+err.Error())
		}
	}))
	m.HandleFunc("POST "+prefix+"/{id}/finish", wrap(func(w http.ResponseWriter, r *http.Request, owner string) {
		id := r.PathValue("id")
		path, sum, err := u.Finish(owner, id)
		if err != nil {
			status := http.StatusInternalServerError
			if errors.Is(err, transfer.ErrNotFound) {
				status = http.StatusNotFound
			} else if errors.Is(err, transfer.ErrSizeMismatch) {
				status = http.StatusConflict
			}
			writeErr(w, status, err.Error())
			return
		}
		v, _ := s.uploadMeta.LoadAndDelete(id)
		meta, _ := v.(uploadMeta)
		to, err := s.resolveTo(owner, meta.to)
		if err != nil { // recipient left meanwhile: keep it for the laptop
			to = []string{items.Host}
			if owner == items.Host {
				writeErr(w, http.StatusBadRequest, err.Error())
				return
			}
		}
		info, _ := os.Stat(path)
		var size int64
		if info != nil {
			size = info.Size()
		}
		it := s.addItem(items.Item{Kind: items.File, Name: filepath.Base(path), Path: path, Size: size, SHA256: sum, From: owner, To: to})
		writeJSON(w, http.StatusOK, s.view(it))
	}))
	m.HandleFunc("DELETE "+prefix+"/{id}", wrap(func(w http.ResponseWriter, r *http.Request, owner string) {
		id := r.PathValue("id")
		s.uploadMeta.Delete(id)
		if err := u.Abort(owner, id); err != nil {
			writeErr(w, http.StatusNotFound, err.Error())
			return
		}
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	}))
}
