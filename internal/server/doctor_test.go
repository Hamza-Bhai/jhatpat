package server

import (
	"context"
	"testing"

	"github.com/Hamza-Bhai/jhatpat/internal/netdoctor"
)

type fakeDoctor struct {
	trouble bool
	seen    bool
	fixed   bool
}

func (f *fakeDoctor) Run(_ context.Context, o netdoctor.Options) netdoctor.Report {
	f.trouble = o.PhoneTrouble
	f.seen = o.PhoneSeen
	return netdoctor.Report{IP: "192.168.1.5", OK: true}
}
func (f *fakeDoctor) FixFirewall() error { f.fixed = true; return nil }

func TestDoctorEndpoints(t *testing.T) {
	s := newTestServer(t)
	fd := &fakeDoctor{}
	s.cfg.Doctor = fd
	p := s.testClient(t, "192.168.1.20:1")
	if rec := p.do("GET", "/api/host/doctor", nil); rec.Code != 403 {
		t.Fatalf("LAN got %d", rec.Code)
	}
	h := hostOf(t, s)
	r := decode[map[string]any](t, h.do("GET", "/api/host/doctor?trouble=1", nil))
	if r["ip"] != "192.168.1.5" || !fd.trouble {
		t.Fatalf("got %v trouble=%v", r, fd.trouble)
	}
	if rec := h.do("POST", "/api/host/doctor/firewall", nil); rec.Code != 200 || !fd.fixed {
		t.Fatalf("fix %d", rec.Code)
	}
}

func TestDoctorKnowsWhenAPhoneConnected(t *testing.T) {
	s := newTestServer(t)
	fd := &fakeDoctor{}
	s.cfg.Doctor = fd
	h := hostOf(t, s)
	h.do("GET", "/api/host/doctor", nil)
	if fd.seen || s.PhoneSeen() {
		t.Fatal("seen before any phone")
	}
	joinApproved(t, s, h, "192.168.1.20:1", "A")
	h.do("GET", "/api/host/doctor", nil)
	if !fd.seen || !s.PhoneSeen() {
		t.Fatal("phone not seen after LAN join")
	}
}

func TestLoopbackJoinDoesNotCountAsPhone(t *testing.T) {
	s := newTestServer(t)
	p := s.testClient(t, "127.0.0.1:1")
	p.do("POST", "/api/join", map[string]string{"pin": s.join.PIN()})
	if s.PhoneSeen() {
		t.Fatal("loopback counted as phone")
	}
}
