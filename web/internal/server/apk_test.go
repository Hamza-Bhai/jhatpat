package server

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
	"time"
)

const lanAddr = "192.168.1.20:4000"

// newAPKServer returns a server whose "APK" is the given bytes, and a clock
// the test can move.
func newAPKServer(t *testing.T, content []byte) (*Server, *time.Time) {
	t.Helper()
	apk := filepath.Join(t.TempDir(), "base.apk")
	if err := os.WriteFile(apk, content, 0o644); err != nil {
		t.Fatal(err)
	}
	s, err := New(Config{Port: 8787, SaveDir: t.TempDir(), ConfigDir: t.TempDir(), APKPath: apk})
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 10, 31, 12, 0, 0, 0, time.UTC)
	s.now = func() time.Time { return now }
	return s, &now
}

func TestAPKDisabledByDefault(t *testing.T) {
	s, _ := newAPKServer(t, []byte("apk"))
	if rec := s.testClient(t, lanAddr).do("GET", "/app.apk", nil); rec.Code != 404 {
		t.Fatalf("got %d, want 404", rec.Code)
	}
}

func TestAPKEnabledServesBytes(t *testing.T) {
	want := bytes.Repeat([]byte("JhatPat"), 1000)
	s, _ := newAPKServer(t, want)
	h := hostOf(t, s)
	if rec := h.do("POST", "/api/host/apk/enable", map[string]int{"minutes": 10}); rec.Code != 200 {
		t.Fatalf("enable: %d %s", rec.Code, rec.Body)
	}
	rec := s.testClient(t, lanAddr).do("GET", "/app.apk", nil)
	if rec.Code != 200 {
		t.Fatalf("got %d", rec.Code)
	}
	if !bytes.Equal(rec.Body.Bytes(), want) {
		t.Fatal("body differs from the APK file")
	}
	if got := rec.Header().Get("Content-Type"); got != "application/vnd.android.package-archive" {
		t.Errorf("Content-Type = %q", got)
	}
	if got := rec.Header().Get("Content-Disposition"); got != `attachment; filename="JhatPat.apk"` {
		t.Errorf("Content-Disposition = %q", got)
	}
}

func TestAPKExpires(t *testing.T) {
	s, now := newAPKServer(t, []byte("apk"))
	h := hostOf(t, s)
	h.do("POST", "/api/host/apk/enable", map[string]int{"minutes": 10})
	*now = now.Add(9 * time.Minute)
	if rec := s.testClient(t, lanAddr).do("GET", "/app.apk", nil); rec.Code != 200 {
		t.Fatalf("after 9 min: %d, want 200", rec.Code)
	}
	*now = now.Add(2 * time.Minute) // 11 minutes in
	if rec := s.testClient(t, lanAddr).do("GET", "/app.apk", nil); rec.Code != 404 {
		t.Fatalf("after 11 min: %d, want 404", rec.Code)
	}
}

func TestAPKDisableStopsServing(t *testing.T) {
	s, _ := newAPKServer(t, []byte("apk"))
	h := hostOf(t, s)
	h.do("POST", "/api/host/apk/enable", map[string]int{"minutes": 10})
	if rec := h.do("POST", "/api/host/apk/disable", nil); rec.Code != 200 {
		t.Fatalf("disable: %d", rec.Code)
	}
	if rec := s.testClient(t, lanAddr).do("GET", "/app.apk", nil); rec.Code != 404 {
		t.Fatalf("got %d, want 404", rec.Code)
	}
}

func TestAPKEnableNeedsHost(t *testing.T) {
	s, _ := newAPKServer(t, []byte("apk"))
	p := s.testClient(t, lanAddr)
	if rec := p.do("POST", "/api/host/apk/enable", map[string]int{"minutes": 10}); rec.Code != 403 {
		t.Fatalf("enable from LAN: %d, want 403", rec.Code)
	}
	if rec := p.do("POST", "/api/host/apk/disable", nil); rec.Code != 403 {
		t.Fatalf("disable from LAN: %d, want 403", rec.Code)
	}
}

func TestAPKMissingFile(t *testing.T) {
	s, err := New(Config{Port: 8787, SaveDir: t.TempDir(), ConfigDir: t.TempDir(), APKPath: filepath.Join(t.TempDir(), "nope.apk")})
	if err != nil {
		t.Fatal(err)
	}
	h := hostOf(t, s)
	state := decode[map[string]any](t, h.do("GET", "/api/host/state", nil))
	apk := state["apk"].(map[string]any)
	if apk["available"] != false {
		t.Fatalf("available = %v, want false", apk["available"])
	}
	if rec := h.do("POST", "/api/host/apk/enable", map[string]int{"minutes": 10}); rec.Code != 404 {
		t.Fatalf("enable with missing file: %d, want 404", rec.Code)
	}
}

func TestAPKNotConfigured(t *testing.T) {
	s := newTestServer(t) // no APKPath
	h := hostOf(t, s)
	state := decode[map[string]any](t, h.do("GET", "/api/host/state", nil))
	if state["apk"].(map[string]any)["available"] != false {
		t.Fatal("available must be false without an APK path")
	}
	if rec := h.do("POST", "/api/host/apk/enable", map[string]int{"minutes": 10}); rec.Code != 404 {
		t.Fatalf("got %d, want 404", rec.Code)
	}
}

func TestAPKRangeRequest(t *testing.T) {
	s, _ := newAPKServer(t, []byte("0123456789abcdefghij"))
	h := hostOf(t, s)
	h.do("POST", "/api/host/apk/enable", map[string]int{"minutes": 10})
	rec := s.testClient(t, lanAddr).do("GET", "/app.apk", nil, "Range", "bytes=0-9")
	if rec.Code != 206 {
		t.Fatalf("got %d, want 206", rec.Code)
	}
	if got := rec.Body.String(); got != "0123456789" {
		t.Fatalf("body %q", got)
	}
}

func TestAPKStateReportsWindow(t *testing.T) {
	s, now := newAPKServer(t, []byte("apk"))
	h := hostOf(t, s)
	apk := func() map[string]any {
		return decode[map[string]any](t, h.do("GET", "/api/host/state", nil))["apk"].(map[string]any)
	}
	if a := apk(); a["available"] != true || a["enabledUntil"] != "" {
		t.Fatalf("before enable: %v", a)
	}
	h.do("POST", "/api/host/apk/enable", map[string]int{"minutes": 5})
	want := now.Add(5 * time.Minute).UTC().Format(time.RFC3339)
	if a := apk(); a["enabledUntil"] != want {
		t.Fatalf("enabledUntil = %v, want %s", a["enabledUntil"], want)
	}
	*now = now.Add(6 * time.Minute)
	if a := apk(); a["enabledUntil"] != "" {
		t.Fatalf("after expiry: %v", a)
	}
}

func TestAPKEnableRejectsBadMinutes(t *testing.T) {
	s, _ := newAPKServer(t, []byte("apk"))
	h := hostOf(t, s)
	for _, m := range []int{-5, 61, 1000} {
		if rec := h.do("POST", "/api/host/apk/enable", map[string]int{"minutes": m}); rec.Code != 400 {
			t.Errorf("minutes %d: got %d, want 400", m, rec.Code)
		}
	}
}
