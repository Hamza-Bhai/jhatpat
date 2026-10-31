package server

import (
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/Hamza-Bhai/jhatpat/web/internal/events"
	"github.com/Hamza-Bhai/jhatpat/web/internal/items"
)

// upload sends data through a chunked-upload endpoint and returns the finished item view.
func upload(t *testing.T, c *client, prefix, name string, data []byte, to map[string]string) map[string]any {
	t.Helper()
	init := c.do("POST", prefix, map[string]any{"name": name, "size": len(data), "to": to})
	if init.Code != 200 {
		t.Fatalf("init: %d %s", init.Code, init.Body)
	}
	id := decode[map[string]any](t, init)["id"].(string)
	if rec := c.do("PUT", prefix+"/"+id+"?offset=0", data); rec.Code != 200 {
		t.Fatalf("put: %d %s", rec.Code, rec.Body)
	}
	fin := c.do("POST", prefix+"/"+id+"/finish", nil)
	if fin.Code != 200 {
		t.Fatalf("finish: %d %s", fin.Code, fin.Body)
	}
	return decode[map[string]any](t, fin)
}

func nextEvent(t *testing.T, ch <-chan events.Event, kind string) (events.Event, bool) {
	t.Helper()
	deadline := time.After(300 * time.Millisecond)
	for {
		select {
		case e := <-ch:
			if e.Type == kind {
				return e, true
			}
		case <-deadline:
			return events.Event{}, false
		}
	}
}

func TestReceivedEventForHostRecipient(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, _ := joinApproved(t, s, h, lanAddr, "Ali")
	ch, stop := s.hub.Subscribe(items.Host)
	defer stop()
	item := upload(t, p, "/api/dev/uploads", "photo.jpg", []byte("jpegdata"), map[string]string{"kind": "host"})
	e, ok := nextEvent(t, ch, "received")
	if !ok {
		t.Fatal("host got no received event")
	}
	raw, _ := json.Marshal(e.Data)
	var d map[string]any
	_ = json.Unmarshal(raw, &d)
	if d["id"] != item["id"] || d["name"] != "photo.jpg" || d["size"].(float64) != 8 {
		t.Fatalf("event data: %v", d)
	}
	path, _ := d["path"].(string)
	if path == "" || !strings.HasSuffix(path, "photo.jpg") {
		t.Fatalf("event path = %q", path)
	}
}

func TestNoReceivedEventForDeviceToDevice(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	a, _ := joinApproved(t, s, h, lanAddr, "A")
	_, bID := joinApproved(t, s, h, "192.168.1.21:4000", "B")
	ch, stop := s.hub.Subscribe(items.Host)
	defer stop()
	upload(t, a, "/api/dev/uploads", "x.bin", []byte("x"), map[string]string{"kind": "device", "id": bID})
	if _, ok := nextEvent(t, ch, "received"); ok {
		t.Fatal("host got a received event for a file that was not addressed to it")
	}
}

func TestNoReceivedEventForHostUpload(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	_, id := joinApproved(t, s, h, lanAddr, "A")
	ch, stop := s.hub.Subscribe(items.Host)
	defer stop()
	upload(t, h, "/api/host/uploads", "x.bin", []byte("x"), map[string]string{"kind": "device", "id": id})
	if _, ok := nextEvent(t, ch, "received"); ok {
		t.Fatal("a file the host sent must not come back as received")
	}
}

func TestReceivedPathNeverInViews(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, _ := joinApproved(t, s, h, lanAddr, "Ali")
	upload(t, p, "/api/dev/uploads", "photo.jpg", []byte("jpegdata"), map[string]string{"kind": "host"})
	// The save folder itself is shown on the host page on purpose; what must never appear is the
	// path of a received file (in JSON, Windows backslashes are doubled).
	filePath := filepath.Join(s.cfg.SaveDir, "photo.jpg")
	forms := []string{filePath, strings.ReplaceAll(filePath, `\`, `\`)}
	for name, body := range map[string]string{
		"dev items":  p.do("GET", "/api/dev/items", nil).Body.String(),
		"host state": h.do("GET", "/api/host/state", nil).Body.String(),
	} {
		for _, f := range forms {
			if strings.Contains(body, f) {
				t.Errorf("%s leaks the received file's path %q: %s", name, f, body)
			}
		}
		if strings.Contains(body, `"path"`) {
			t.Errorf("%s has a path field: %s", name, body)
		}
	}
}

func TestMovedItemIs410(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, _ := joinApproved(t, s, h, lanAddr, "Ali")
	item := upload(t, p, "/api/dev/uploads", "photo.jpg", []byte("jpegdata"), map[string]string{"kind": "host"})
	id := item["id"].(string)
	if rec := h.do("POST", "/api/host/items/"+id+"/moved", map[string]string{"where": "Gallery"}); rec.Code != 200 {
		t.Fatalf("moved: %d %s", rec.Code, rec.Body)
	}
	for _, path := range []string{"/api/host/items/" + id + "/download", "/api/host/items/" + id + "/view"} {
		rec := h.do("GET", path, nil)
		if rec.Code != 410 || decode[map[string]string](t, rec)["error"] != "moved" {
			t.Errorf("%s: %d %s", path, rec.Code, rec.Body)
		}
	}
	if rec := p.do("GET", "/api/dev/items/"+id+"/download", nil); rec.Code != 410 {
		t.Errorf("the sender's download of a moved item: %d, want 410", rec.Code)
	}
}

func TestMovedFlagInViews(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, _ := joinApproved(t, s, h, lanAddr, "Ali")
	id := upload(t, p, "/api/dev/uploads", "photo.jpg", []byte("jpegdata"), map[string]string{"kind": "host"})["id"].(string)
	h.do("POST", "/api/host/items/"+id+"/moved", map[string]string{"where": "Gallery"})
	list := decode[map[string]any](t, h.do("GET", "/api/host/state", nil))["items"].([]any)
	it := list[0].(map[string]any)
	if it["moved"] != true || it["movedTo"] != "Gallery" {
		t.Fatalf("item view: %v", it)
	}
}

func TestMovedNeedsHostAndKnownItem(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, _ := joinApproved(t, s, h, lanAddr, "Ali")
	id := upload(t, p, "/api/dev/uploads", "photo.jpg", []byte("jpegdata"), map[string]string{"kind": "host"})["id"].(string)
	if rec := p.do("POST", "/api/host/items/"+id+"/moved", map[string]string{"where": "Gallery"}); rec.Code != 403 {
		t.Errorf("device: %d, want 403", rec.Code)
	}
	if rec := h.do("POST", "/api/host/items/nope/moved", map[string]string{"where": "Gallery"}); rec.Code != 404 {
		t.Errorf("unknown item: %d, want 404", rec.Code)
	}
	if rec := h.do("POST", "/api/host/items/"+id+"/moved", map[string]string{"where": ""}); rec.Code != 400 {
		t.Errorf("empty where: %d, want 400", rec.Code)
	}
}
