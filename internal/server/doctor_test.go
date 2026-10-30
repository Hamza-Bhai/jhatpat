package server

import (
	"context"
	"testing"

	"jhatpat/internal/netdoctor"
)

type fakeDoctor struct {
	trouble bool
	fixed   bool
}

func (f *fakeDoctor) Run(_ context.Context, o netdoctor.Options) netdoctor.Report {
	f.trouble = o.PhoneTrouble
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
