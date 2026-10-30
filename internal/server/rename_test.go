package server

import "testing"

func TestDeviceCanRenameItself(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, id := joinApproved(t, s, h, "192.168.1.20:1", "Android Phone")
	if rec := p.do("POST", "/api/dev/rename", map[string]string{"name": "  Ali ka Phone  "}); rec.Code != 200 {
		t.Fatalf("rename %d %s", rec.Code, rec.Body)
	}
	if me := decode[map[string]string](t, p.do("GET", "/api/dev/me", nil)); me["name"] != "Ali ka Phone" {
		t.Fatalf("me %v", me)
	}
	d, _ := s.devices.Get(id)
	if n, _ := s.groups.KnownName(d.PersistentID); n != "Ali ka Phone" {
		t.Fatalf("remembered %q", n)
	}
	if rec := p.do("POST", "/api/dev/rename", map[string]string{"name": "   "}); rec.Code != 400 {
		t.Fatalf("empty name got %d", rec.Code)
	}
}

func TestRenameNeedsApproval(t *testing.T) {
	s := newTestServer(t)
	p := s.testClient(t, "192.168.1.20:1")
	p.do("POST", "/api/join", map[string]string{"pin": s.join.PIN(), "name": "X"})
	if rec := p.do("POST", "/api/dev/rename", map[string]string{"name": "Hacker"}); rec.Code != 401 {
		t.Fatalf("pending device rename got %d", rec.Code)
	}
}

func TestRejoinKeepsRememberedName(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, _ := joinApproved(t, s, h, "192.168.1.20:1", "Android Phone")
	p.do("POST", "/api/dev/rename", map[string]string{"name": "Ali ka Phone"})

	// JhatPat restarts; the phone joins again with the page's default guess.
	s2, err := New(s.cfg)
	if err != nil {
		t.Fatal(err)
	}
	p.s = s2
	ua := "Mozilla/5.0 (Linux; Android 14)"
	rec := p.do("POST", "/api/join", map[string]string{"pin": s2.join.PIN(), "name": "Android Phone"}, "User-Agent", ua)
	id := decode[map[string]string](t, rec)["id"]
	if d, _ := s2.devices.Get(id); d.Name != "Ali ka Phone" {
		t.Fatalf("name %q", d.Name)
	}
	// A name the user actually typed wins over the remembered one.
	p2 := s2.testClient(t, "192.168.1.20:1")
	p2.cookies["jp_pid"] = p.cookies["jp_pid"]
	rec = p2.do("POST", "/api/join", map[string]string{"pin": s2.join.PIN(), "name": "Ammi"}, "User-Agent", ua)
	id = decode[map[string]string](t, rec)["id"]
	if d, _ := s2.devices.Get(id); d.Name != "Ammi" {
		t.Fatalf("typed name lost: %q", d.Name)
	}
}
