package server

import (
	"strings"
	"testing"
)

func TestSetIPChangesJoinURL(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	if rec := h.do("POST", "/api/host/ip", map[string]string{"ip": "192.168.43.1"}); rec.Code != 200 {
		t.Fatalf("set ip: %d %s", rec.Code, rec.Body)
	}
	state := decode[map[string]any](t, h.do("GET", "/api/host/state", nil))
	join, _ := state["joinURL"].(string)
	if !strings.HasPrefix(join, "http://192.168.43.1:8787/j/") {
		t.Fatalf("joinURL = %q", join)
	}
}

func TestSetIPRejectsPublicOrGarbage(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	for _, bad := range []string{"8.8.8.8", "abc", "", "::1", "fd00::1", "192.168.1.1:80", "192.168.1.1/24"} {
		if rec := h.do("POST", "/api/host/ip", map[string]string{"ip": bad}); rec.Code != 400 {
			t.Errorf("ip %q: got %d, want 400", bad, rec.Code)
		}
	}
	state := decode[map[string]any](t, h.do("GET", "/api/host/state", nil))
	if join, _ := state["joinURL"].(string); join != "" {
		t.Fatalf("a rejected IP must not change joinURL, got %q", join)
	}
}

func TestSetIPHostOnly(t *testing.T) {
	s := newTestServer(t)
	p := s.testClient(t, "192.168.1.20:4000")
	if rec := p.do("POST", "/api/host/ip", map[string]string{"ip": "192.168.43.1"}); rec.Code != 403 {
		t.Fatalf("got %d, want 403", rec.Code)
	}
}

func TestSetIPMethodAcceptsAnyAddress(t *testing.T) {
	// The method is for trusted callers (flags); only the HTTP route validates.
	s := newTestServer(t)
	s.SetIP("127.0.0.1")
	if s.IP() != "127.0.0.1" {
		t.Fatalf("IP() = %q", s.IP())
	}
}
