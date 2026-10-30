package server

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"testing/fstest"
)

func TestStaticServed(t *testing.T) {
	s := newTestServer(t)
	s2, err := New(Config{Port: 1, SaveDir: s.cfg.SaveDir, ConfigDir: s.cfg.ConfigDir, Web: fstest.MapFS{
		"host.html":               {Data: []byte("HOST")},
		"static/css/sunnypop.css": {Data: []byte("body{}")},
	}})
	if err != nil {
		t.Fatal(err)
	}
	c := s2.testClient(t, "192.168.1.20:1")
	rec := c.do("GET", "/static/css/sunnypop.css", nil)
	if rec.Code != 200 || !strings.HasPrefix(rec.Header().Get("Content-Type"), "text/css") {
		t.Fatalf("got %d %s", rec.Code, rec.Header().Get("Content-Type"))
	}
}

func TestBrowseHostOnly(t *testing.T) {
	s := newTestServer(t)
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "a.txt"), []byte("hello"), 0o644)
	_ = os.Mkdir(filepath.Join(dir, "Sub"), 0o755)
	p := s.testClient(t, "192.168.1.20:1")
	if rec := p.do("GET", "/api/host/browse?dir="+dir, nil); rec.Code != 403 {
		t.Fatalf("LAN got %d", rec.Code)
	}
	h := hostOf(t, s)
	res := decode[map[string]any](t, h.do("GET", "/api/host/browse?dir="+dir, nil))
	entries := res["entries"].([]any)
	if len(entries) != 2 {
		t.Fatalf("entries %v", entries)
	}
	first := entries[0].(map[string]any)
	if first["name"] != "Sub" || first["isDir"] != true { // folders first
		t.Fatalf("first %v", first)
	}
	if res["parent"] == "" {
		t.Fatal("no parent")
	}
}

func TestBrowseDefaultsListRoots(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	res := decode[map[string]any](t, h.do("GET", "/api/host/browse", nil))
	if roots, ok := res["roots"].([]any); !ok || len(roots) == 0 {
		t.Fatalf("roots %v", res["roots"])
	}
}
