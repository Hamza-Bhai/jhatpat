package server

import (
	"strings"
	"testing"
	"testing/fstest"

	"github.com/Hamza-Bhai/jhatpat/web/web"
)

func newPhoneHost(t *testing.T) *Server {
	t.Helper()
	s, err := New(Config{Port: 8787, SaveDir: t.TempDir(), ConfigDir: t.TempDir(), HostName: "Phone", HostKind: "phone"})
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestHostNameInTargetsAndMe(t *testing.T) {
	s := newPhoneHost(t)
	h := hostOf(t, s)
	p, _ := joinApproved(t, s, h, lanAddr, "Ali")
	for _, path := range []string{"/api/dev/targets", "/api/dev/me"} {
		got := decode[map[string]any](t, p.do("GET", path, nil))
		if got["hostName"] != "Phone" || got["hostKind"] != "phone" {
			t.Errorf("%s: hostName=%v hostKind=%v", path, got["hostName"], got["hostKind"])
		}
	}
}

func TestHostIdentityDefaultsToLaptop(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	p, _ := joinApproved(t, s, h, lanAddr, "Ali")
	got := decode[map[string]any](t, p.do("GET", "/api/dev/targets", nil))
	if got["hostName"] != "Laptop" || got["hostKind"] != "laptop" {
		t.Fatalf("defaults: hostName=%v hostKind=%v", got["hostName"], got["hostKind"])
	}
	state := decode[map[string]any](t, h.do("GET", "/api/host/state", nil))
	if state["hostName"] != "Laptop" || state["hostKind"] != "laptop" {
		t.Fatalf("state: %v %v", state["hostName"], state["hostKind"])
	}
}

func TestFromNameUsesHostName(t *testing.T) {
	s := newPhoneHost(t)
	h := hostOf(t, s)
	p, id := joinApproved(t, s, h, lanAddr, "Ali")
	if rec := h.do("POST", "/api/host/send", map[string]any{"text": "hi", "to": map[string]string{"kind": "device", "id": id}}); rec.Code != 200 {
		t.Fatalf("send: %d %s", rec.Code, rec.Body)
	}
	items := decode[[]map[string]any](t, p.do("GET", "/api/dev/items", nil))
	if len(items) != 1 || items[0]["fromName"] != "Phone" {
		t.Fatalf("items: %v", items)
	}
}

func TestStateDoctorFlag(t *testing.T) {
	s := newTestServer(t) // Doctor is nil
	state := decode[map[string]any](t, hostOf(t, s).do("GET", "/api/host/state", nil))
	if state["doctor"] != false {
		t.Fatalf("doctor = %v, want false without a Doctor", state["doctor"])
	}
	s2, err := New(Config{Port: 8787, SaveDir: t.TempDir(), ConfigDir: t.TempDir(), Doctor: &fakeDoctor{}})
	if err != nil {
		t.Fatal(err)
	}
	state = decode[map[string]any](t, hostOf(t, s2).do("GET", "/api/host/state", nil))
	if state["doctor"] != true {
		t.Fatalf("doctor = %v, want true with a Doctor", state["doctor"])
	}
}

func TestPagesCarryHostIdentity(t *testing.T) {
	s, err := New(Config{Port: 8787, SaveDir: t.TempDir(), ConfigDir: t.TempDir(), HostName: "Phone", HostKind: "phone",
		Web: fstest.MapFS{"join.html": {Data: []byte("<html><head><title>x</title></head><body>j</body></html>")}}})
	if err != nil {
		t.Fatal(err)
	}
	body := s.testClient(t, lanAddr).do("GET", "/", nil).Body.String()
	if !strings.Contains(body, `window.JPHOST=`) || !strings.Contains(body, `"name":"Phone"`) || !strings.Contains(body, `"kind":"phone"`) {
		t.Fatalf("join page lacks host identity: %s", body)
	}
	if strings.Index(body, "JPHOST") > strings.Index(body, "</head>") {
		t.Fatal("identity script must be inside <head>")
	}
}

func TestHostNameCannotBreakOutOfScript(t *testing.T) {
	s, err := New(Config{Port: 8787, SaveDir: t.TempDir(), ConfigDir: t.TempDir(), HostName: `</script><b>x`,
		Web: fstest.MapFS{"join.html": {Data: []byte("<html><head></head><body></body></html>")}}})
	if err != nil {
		t.Fatal(err)
	}
	body := s.testClient(t, lanAddr).do("GET", "/", nil).Body.String()
	if strings.Count(body, "</script>") != 1 {
		t.Fatalf("host name closed the script tag: %s", body)
	}
}

func TestHostJSMentionsBridge(t *testing.T) {
	b, err := web.FS.ReadFile("static/js/host.js")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(b), "JhatPatApp") {
		t.Fatal("host.js must check for the Android bridge (window.JhatPatApp)")
	}
}
