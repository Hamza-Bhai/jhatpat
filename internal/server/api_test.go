package server

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const hostAddr = "127.0.0.1:50000"

type client struct {
	t       *testing.T
	s       *Server
	addr    string
	cookies map[string]string
}

func (s *Server) testClient(t *testing.T, addr string) *client {
	return &client{t: t, s: s, addr: addr, cookies: map[string]string{}}
}

func (c *client) do(method, path string, body any, hdr ...string) *httptest.ResponseRecorder {
	c.t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case []byte:
		rd = bytes.NewReader(b)
	default:
		raw, _ := json.Marshal(b)
		rd = bytes.NewReader(raw)
	}
	req := httptest.NewRequest(method, path, rd)
	req.RemoteAddr = c.addr
	for k, v := range c.cookies {
		req.AddCookie(&http.Cookie{Name: k, Value: v})
	}
	for i := 0; i+1 < len(hdr); i += 2 {
		req.Header.Set(hdr[i], hdr[i+1])
	}
	rec := httptest.NewRecorder()
	c.s.Handler().ServeHTTP(rec, req)
	for _, ck := range rec.Result().Cookies() {
		c.cookies[ck.Name] = ck.Value
	}
	return rec
}

func decode[T any](t *testing.T, rec *httptest.ResponseRecorder) T {
	t.Helper()
	var v T
	if err := json.Unmarshal(rec.Body.Bytes(), &v); err != nil {
		t.Fatalf("decode %q: %v", rec.Body.String(), err)
	}
	return v
}

func hostOf(t *testing.T, s *Server) *client {
	h := s.testClient(t, hostAddr)
	if rec := h.do("GET", "/host?t="+s.HostToken(), nil); rec.Code != 303 || rec.Header().Get("Location") != "/host" {
		t.Fatalf("host token link: %d %q", rec.Code, rec.Header().Get("Location"))
	}
	if rec := h.do("GET", "/host", nil); rec.Code != 200 {
		t.Fatalf("host page %d", rec.Code)
	}
	return h
}

// joinApproved joins a phone by PIN and approves it; returns the client and device id.
func joinApproved(t *testing.T, s *Server, h *client, addr, name string) (*client, string) {
	t.Helper()
	p := s.testClient(t, addr)
	rec := p.do("POST", "/api/join", map[string]string{"pin": s.join.PIN(), "name": name})
	if rec.Code != 200 {
		t.Fatalf("join %d %s", rec.Code, rec.Body)
	}
	id := decode[map[string]string](t, rec)["id"]
	if rec := h.do("POST", "/api/host/devices/"+id+"/approve", nil); rec.Code != 200 {
		t.Fatalf("approve %d", rec.Code)
	}
	return p, id
}

func TestHostRoutesRejectNonLoopback(t *testing.T) {
	s := newTestServer(t)
	p := s.testClient(t, "192.168.1.20:4000")
	if rec := p.do("GET", "/api/host/state", nil); rec.Code != 403 {
		t.Fatalf("got %d", rec.Code)
	}
	if rec := p.do("GET", "/host?t="+s.HostToken(), nil); rec.Code != 403 {
		t.Fatalf("host page from LAN got %d", rec.Code)
	}
	h := s.testClient(t, hostAddr)
	if rec := h.do("GET", "/api/host/state", nil); rec.Code != 403 {
		t.Fatalf("loopback without host cookie got %d", rec.Code)
	}
}

func TestPINJoinNeedsApproval(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p := s.testClient(t, "192.168.1.20:4000")
	if rec := p.do("POST", "/api/join", map[string]string{"pin": "x", "name": "P"}); rec.Code != 401 {
		t.Fatalf("wrong pin got %d", rec.Code)
	}
	rec := p.do("POST", "/api/join", map[string]string{"pin": s.join.PIN(), "name": "Ali"})
	id := decode[map[string]string](t, rec)["id"]
	if st := decode[map[string]string](t, p.do("GET", "/api/join/status", nil))["state"]; st != "pending" {
		t.Fatalf("state %s", st)
	}
	if rec := p.do("GET", "/api/dev/items", nil); rec.Code != 401 {
		t.Fatalf("pending got items %d", rec.Code)
	}
	h.do("POST", "/api/host/devices/"+id+"/approve", nil)
	if st := decode[map[string]string](t, p.do("GET", "/api/join/status", nil))["state"]; st != "approved" {
		t.Fatalf("state %s", st)
	}
	if rec := p.do("GET", "/api/dev/items", nil); rec.Code != 200 {
		t.Fatalf("approved items %d", rec.Code)
	}
}

func TestPINLockoutReturns429(t *testing.T) {
	s := newTestServer(t)
	p := s.testClient(t, "192.168.1.21:4000")
	for i := 0; i < 5; i++ {
		p.do("POST", "/api/join", map[string]string{"pin": "abcd"})
	}
	if rec := p.do("POST", "/api/join", map[string]string{"pin": s.join.PIN()}); rec.Code != 429 {
		t.Fatalf("got %d", rec.Code)
	}
}

func TestSecretLinkJoin(t *testing.T) {
	s := newTestServer(t)
	p := s.testClient(t, "192.168.1.20:4000")
	if rec := p.do("GET", "/j/wrong", nil); rec.Code != 404 {
		t.Fatalf("wrong secret %d", rec.Code)
	}
	if rec := p.do("GET", "/j/"+s.join.Secret(), nil); rec.Code != http.StatusSeeOther {
		t.Fatalf("secret %d", rec.Code)
	}
	if st := decode[map[string]string](t, p.do("GET", "/api/join/status", nil))["state"]; st != "pending" {
		t.Fatalf("state %s", st)
	}
}

func TestBlockedDeviceCannotRejoin(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p := s.testClient(t, "192.168.1.20:4000")
	id := decode[map[string]string](t, p.do("POST", "/api/join", map[string]string{"pin": s.join.PIN()}))["id"]
	h.do("POST", "/api/host/devices/"+id+"/block", nil)
	if rec := p.do("POST", "/api/join", map[string]string{"pin": s.join.PIN()}); rec.Code != 403 {
		t.Fatalf("blocked rejoin got %d", rec.Code)
	}
}

func sendText(t *testing.T, h *client, text string, to map[string]string) string {
	t.Helper()
	rec := h.do("POST", "/api/host/send", map[string]any{"text": text, "to": to})
	if rec.Code != 200 {
		t.Fatalf("send %d %s", rec.Code, rec.Body)
	}
	return decode[[]map[string]any](t, rec)[0]["id"].(string)
}

func itemCount(t *testing.T, p *client) int {
	return len(decode[[]map[string]any](t, p.do("GET", "/api/dev/items", nil)))
}

func TestDeviceIsolation(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	a, aid := joinApproved(t, s, h, "192.168.1.20:1", "A")
	b, _ := joinApproved(t, s, h, "192.168.1.21:1", "B")
	id := sendText(t, h, "secret for A", map[string]string{"kind": "device", "id": aid})
	if itemCount(t, b) != 0 {
		t.Fatal("B sees A's item")
	}
	if rec := b.do("GET", "/api/dev/items/"+id+"/download", nil); rec.Code != 404 {
		t.Fatalf("B download got %d", rec.Code)
	}
	if itemCount(t, a) != 1 {
		t.Fatal("A missing item")
	}
	if rec := a.do("GET", "/api/dev/items/"+id+"/download", nil); rec.Code != 200 || rec.Body.String() != "secret for A" {
		t.Fatalf("A download %d %q", rec.Code, rec.Body)
	}
}

func TestGroupSend(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	a, aid := joinApproved(t, s, h, "192.168.1.20:1", "A")
	b, bid := joinApproved(t, s, h, "192.168.1.21:1", "B")
	c, _ := joinApproved(t, s, h, "192.168.1.22:1", "C")
	g := decode[map[string]any](t, h.do("POST", "/api/host/groups", map[string]string{"name": "Family"}))
	gid := g["id"].(string)
	pa, _ := s.devices.Get(aid)
	pb, _ := s.devices.Get(bid)
	if rec := h.do("PATCH", "/api/host/groups/"+gid, map[string]any{"members": []string{pa.PersistentID, pb.PersistentID}}); rec.Code != 200 {
		t.Fatalf("members %d", rec.Code)
	}
	sendText(t, h, "family", map[string]string{"kind": "group", "id": gid})
	if itemCount(t, a) != 1 || itemCount(t, b) != 1 || itemCount(t, c) != 0 {
		t.Fatal("group visibility wrong")
	}
}

func TestGroupSkipsUnapprovedMembers(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	_, aid := joinApproved(t, s, h, "192.168.1.20:1", "A")
	p := s.testClient(t, "192.168.1.21:1")
	pid := decode[map[string]string](t, p.do("POST", "/api/join", map[string]string{"pin": s.join.PIN()}))["id"]
	gid := decode[map[string]any](t, h.do("POST", "/api/host/groups", map[string]string{"name": "G"}))["id"].(string)
	da, _ := s.devices.Get(aid)
	dp, _ := s.devices.Get(pid)
	h.do("PATCH", "/api/host/groups/"+gid, map[string]any{"members": []string{da.PersistentID, dp.PersistentID}})
	rec := h.do("POST", "/api/host/send", map[string]any{"text": "x", "to": map[string]string{"kind": "group", "id": gid}})
	to := decode[[]map[string]any](t, rec)[0]["to"].([]any)
	if len(to) != 1 || to[0] != aid {
		t.Fatalf("to %v", to)
	}
}

func TestUploadResumeOverHTTP(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, _ := joinApproved(t, s, h, "192.168.1.20:1", "A")
	evs, stop := s.hub.Subscribe("host")
	defer stop()
	data := bytes.Repeat([]byte("jhatpat!"), (10<<20)/8)
	up := decode[map[string]any](t, p.do("POST", "/api/dev/uploads", map[string]any{"name": "clip.mp4", "size": len(data)}))
	id := up["id"].(string)
	if rec := p.do("PUT", "/api/dev/uploads/"+id+"?offset=0", data[:8<<20]); rec.Code != 200 {
		t.Fatalf("put1 %d %s", rec.Code, rec.Body)
	}
	off := int(decode[map[string]any](t, p.do("GET", "/api/dev/uploads/"+id, nil))["offset"].(float64))
	if off != 8<<20 {
		t.Fatalf("offset %d", off)
	}
	if rec := p.do("PUT", "/api/dev/uploads/"+id+"?offset=0", data[:10]); rec.Code != 409 {
		t.Fatalf("stale offset got %d", rec.Code)
	}
	p.do("PUT", "/api/dev/uploads/"+id+"?offset=8388608", data[off:])
	if rec := p.do("POST", "/api/dev/uploads/"+id+"/finish", nil); rec.Code != 200 {
		t.Fatalf("finish %d %s", rec.Code, rec.Body)
	}
	got, err := os.ReadFile(filepath.Join(s.cfg.SaveDir, "clip.mp4"))
	if err != nil || !bytes.Equal(got, data) {
		t.Fatal("saved file wrong", err)
	}
	deadline := time.After(time.Second)
	for {
		select {
		case e := <-evs:
			if e.Type == "item" {
				return
			}
		case <-deadline:
			t.Fatal("host got no item event")
		}
	}
}

func TestRangeDownload(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	a, aid := joinApproved(t, s, h, "192.168.1.20:1", "A")
	f := filepath.Join(t.TempDir(), "doc.txt")
	_ = os.WriteFile(f, []byte("0123456789abcdef"), 0o644)
	rec := h.do("POST", "/api/host/send", map[string]any{"paths": []string{f}, "to": map[string]string{"kind": "device", "id": aid}})
	id := decode[[]map[string]any](t, rec)[0]["id"].(string)
	rec = a.do("GET", "/api/dev/items/"+id+"/download", nil, "Range", "bytes=5-9")
	if rec.Code != 206 || rec.Body.String() != "56789" {
		t.Fatalf("got %d %q", rec.Code, rec.Body)
	}
	if !strings.Contains(rec.Header().Get("Content-Disposition"), "doc.txt") {
		t.Fatal("no filename")
	}
}

func TestFolderDownloadIsZip(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	a, aid := joinApproved(t, s, h, "192.168.1.20:1", "A")
	dir := filepath.Join(t.TempDir(), "Pics")
	_ = os.MkdirAll(dir, 0o755)
	_ = os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("x"), 0o644)
	rec := h.do("POST", "/api/host/send", map[string]any{"paths": []string{dir}, "to": map[string]string{"kind": "device", "id": aid}})
	it := decode[[]map[string]any](t, rec)[0]
	if it["kind"] != "folder" {
		t.Fatalf("kind %v", it["kind"])
	}
	rec = a.do("GET", "/api/dev/items/"+it["id"].(string)+"/download", nil)
	if rec.Header().Get("Content-Type") != "application/zip" || !strings.Contains(rec.Header().Get("Content-Disposition"), "Pics.zip") {
		t.Fatalf("headers %v", rec.Header())
	}
}

func TestSendMissingPathFails(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	_, aid := joinApproved(t, s, h, "192.168.1.20:1", "A")
	rec := h.do("POST", "/api/host/send", map[string]any{"paths": []string{filepath.Join(t.TempDir(), "nope")}, "to": map[string]string{"kind": "device", "id": aid}})
	if rec.Code != 400 {
		t.Fatalf("got %d", rec.Code)
	}
}

func TestTextTooLarge(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, _ := joinApproved(t, s, h, "192.168.1.20:1", "A")
	rec := p.do("POST", "/api/dev/text", map[string]any{"text": strings.Repeat("a", 1<<20+1)})
	if rec.Code != 413 {
		t.Fatalf("got %d", rec.Code)
	}
}

func TestDeviceTextGoesToHostByDefault(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, _ := joinApproved(t, s, h, "192.168.1.20:1", "A")
	if rec := p.do("POST", "/api/dev/text", map[string]any{"text": "https://example.com"}); rec.Code != 200 {
		t.Fatalf("got %d", rec.Code)
	}
	st := decode[map[string]any](t, h.do("GET", "/api/host/state", nil))
	if n := len(st["items"].([]any)); n != 1 {
		t.Fatalf("host items %d", n)
	}
}

func TestKickedDeviceLosesAccess(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, id := joinApproved(t, s, h, "192.168.1.20:1", "A")
	h.do("POST", "/api/host/devices/"+id+"/kick", nil)
	if rec := p.do("GET", "/api/dev/items", nil); rec.Code != 401 {
		t.Fatalf("got %d", rec.Code)
	}
}

func TestCrossOriginPostRejected(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	rec := h.do("POST", "/api/host/pin/regenerate", nil, "Origin", "http://evil.example")
	if rec.Code != 403 {
		t.Fatalf("got %d", rec.Code)
	}
}

func TestRestartRequiresApproval(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, _ := joinApproved(t, s, h, "192.168.1.20:1", "A")
	s2, err := New(s.cfg)
	if err != nil {
		t.Fatal(err)
	}
	p.s = s2
	if rec := p.do("GET", "/api/dev/items", nil); rec.Code != 401 {
		t.Fatalf("got %d", rec.Code)
	}
}

func TestApproveRemembersName(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	_, id := joinApproved(t, s, h, "192.168.1.20:1", "Mom's Phone")
	d, _ := s.devices.Get(id)
	if n, ok := s.groups.KnownName(d.PersistentID); !ok || n != "Mom's Phone" {
		t.Fatalf("got %q", n)
	}
}

func TestSecurityHeaders(t *testing.T) {
	s := newTestServer(t)
	rec := s.testClient(t, hostAddr).do("GET", "/api/health", nil)
	for k, want := range map[string]string{
		"X-Content-Type-Options": "nosniff",
		"X-Frame-Options":        "SAMEORIGIN",
		"Referrer-Policy":        "no-referrer",
	} {
		if got := rec.Header().Get(k); got != want {
			t.Errorf("%s = %q, want %q", k, got, want)
		}
	}
}
