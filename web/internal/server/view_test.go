package server

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// sendFile makes the host send a file with the given name and content to device id.
func sendFile(t *testing.T, h *client, id, name, content string) string {
	t.Helper()
	f := filepath.Join(t.TempDir(), name)
	_ = os.WriteFile(f, []byte(content), 0o644)
	rec := h.do("POST", "/api/host/send", map[string]any{"paths": []string{f}, "to": map[string]string{"kind": "device", "id": id}})
	if rec.Code != 200 {
		t.Fatalf("send %d %s", rec.Code, rec.Body)
	}
	return decode[[]map[string]any](t, rec)[0]["id"].(string)
}

func TestViewImageInline(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	a, aid := joinApproved(t, s, h, "192.168.1.20:1", "A")
	id := sendFile(t, h, aid, "beach.JPG", "jpegbytes")
	rec := a.do("GET", "/api/dev/items/"+id+"/view", nil)
	if rec.Code != 200 || rec.Header().Get("Content-Type") != "image/jpeg" {
		t.Fatalf("got %d %q", rec.Code, rec.Header().Get("Content-Type"))
	}
	if !strings.HasPrefix(rec.Header().Get("Content-Disposition"), "inline") || rec.Header().Get("X-Content-Type-Options") != "nosniff" {
		t.Fatalf("headers %v", rec.Header())
	}
}

func TestViewVideoSupportsRange(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	a, aid := joinApproved(t, s, h, "192.168.1.20:1", "A")
	id := sendFile(t, h, aid, "clip.mp4", "0123456789")
	rec := a.do("GET", "/api/dev/items/"+id+"/view", nil, "Range", "bytes=2-4")
	if rec.Code != 206 || rec.Body.String() != "234" || rec.Header().Get("Content-Type") != "video/mp4" {
		t.Fatalf("got %d %q %q", rec.Code, rec.Body, rec.Header().Get("Content-Type"))
	}
}

func TestViewUnsafeTypeForcedDownload(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	a, aid := joinApproved(t, s, h, "192.168.1.20:1", "A")
	for _, name := range []string{"evil.html", "evil.svg", "evil.htm", "noext"} {
		id := sendFile(t, h, aid, name, "<script>alert(1)</script>")
		rec := a.do("GET", "/api/dev/items/"+id+"/view", nil)
		if ct := rec.Header().Get("Content-Type"); ct != "application/octet-stream" {
			t.Errorf("%s: content-type %q", name, ct)
		}
		if !strings.HasPrefix(rec.Header().Get("Content-Disposition"), "attachment") {
			t.Errorf("%s: disposition %q", name, rec.Header().Get("Content-Disposition"))
		}
	}
}

func TestViewRespectsPrivacy(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	_, aid := joinApproved(t, s, h, "192.168.1.20:1", "A")
	b, _ := joinApproved(t, s, h, "192.168.1.21:1", "B")
	id := sendFile(t, h, aid, "a.jpg", "x")
	if rec := b.do("GET", "/api/dev/items/"+id+"/view", nil); rec.Code != 404 {
		t.Fatalf("got %d", rec.Code)
	}
}

func TestHostCanViewAndDownloadPhoneUpload(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, _ := joinApproved(t, s, h, "192.168.1.20:1", "A")
	up := decode[map[string]any](t, p.do("POST", "/api/dev/uploads", map[string]any{"name": "pic.png", "size": 3}))
	id := up["id"].(string)
	p.do("PUT", "/api/dev/uploads/"+id+"?offset=0", []byte("png"))
	item := decode[map[string]any](t, p.do("POST", "/api/dev/uploads/"+id+"/finish", nil))
	iid := item["id"].(string)
	rec := h.do("GET", "/api/host/items/"+iid+"/view", nil)
	if rec.Code != 200 || rec.Header().Get("Content-Type") != "image/png" || rec.Body.String() != "png" {
		t.Fatalf("view %d %q", rec.Code, rec.Header().Get("Content-Type"))
	}
	rec = h.do("GET", "/api/host/items/"+iid+"/download", nil)
	if rec.Code != 200 || !strings.HasPrefix(rec.Header().Get("Content-Disposition"), "attachment") {
		t.Fatalf("download %d %v", rec.Code, rec.Header())
	}
	lan := s.testClient(t, "192.168.1.30:1")
	if rec := lan.do("GET", "/api/host/items/"+iid+"/view", nil); rec.Code != 403 {
		t.Fatalf("LAN got %d", rec.Code)
	}
}
