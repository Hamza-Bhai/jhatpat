package server

import "testing"

func TestTransfersAreHostOnly(t *testing.T) {
	s := newTestServer(t)
	if rec := s.testClient(t, lanAddr).do("GET", "/api/host/transfers", nil); rec.Code != 403 {
		t.Fatalf("LAN got %d, want 403", rec.Code)
	}
}

func TestTransfersListDeviceUploadsInProgress(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	empty := decode[map[string]any](t, h.do("GET", "/api/host/transfers", nil))
	if list := empty["transfers"].([]any); len(list) != 0 {
		t.Fatalf("expected none, got %v", list)
	}
	p, _ := joinApproved(t, s, h, lanAddr, "Ali")
	init := p.do("POST", "/api/dev/uploads", map[string]any{"name": "clip.mp4", "size": 100, "to": map[string]string{"kind": "host"}})
	id := decode[map[string]any](t, init)["id"].(string)
	if rec := p.do("PUT", "/api/dev/uploads/"+id+"?offset=0", make([]byte, 40)); rec.Code != 200 {
		t.Fatalf("put: %d %s", rec.Code, rec.Body)
	}
	list := decode[map[string]any](t, h.do("GET", "/api/host/transfers", nil))["transfers"].([]any)
	if len(list) != 1 {
		t.Fatalf("transfers = %v", list)
	}
	tr := list[0].(map[string]any)
	if tr["name"] != "clip.mp4" || tr["from"] != "Ali" || tr["size"].(float64) != 100 || tr["received"].(float64) != 40 {
		t.Fatalf("transfer = %v", tr)
	}
	// once it finishes it is no longer in progress
	p.do("PUT", "/api/dev/uploads/"+id+"?offset=40", make([]byte, 60))
	if rec := p.do("POST", "/api/dev/uploads/"+id+"/finish", nil); rec.Code != 200 {
		t.Fatalf("finish: %d %s", rec.Code, rec.Body)
	}
	after := decode[map[string]any](t, h.do("GET", "/api/host/transfers", nil))["transfers"].([]any)
	if len(after) != 0 {
		t.Fatalf("after finish: %v", after)
	}
}
