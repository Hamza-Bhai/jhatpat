package server

import (
	"bytes"
	"image/png"
	"testing"
)

func TestQRPNG(t *testing.T) {
	s := newTestServer(t)
	s.SetJoinURL(func() string { return "http://192.168.1.5:8787/j/abc" })
	h := hostOf(t, s)
	rec := h.do("GET", "/api/host/qr.png", nil)
	if rec.Code != 200 || rec.Header().Get("Content-Type") != "image/png" {
		t.Fatalf("got %d %s", rec.Code, rec.Header().Get("Content-Type"))
	}
	if _, err := png.Decode(bytes.NewReader(rec.Body.Bytes())); err != nil {
		t.Fatal(err)
	}
}

func TestQRUnavailableWithoutURL(t *testing.T) {
	s := newTestServer(t)
	h := hostOf(t, s)
	if rec := h.do("GET", "/api/host/qr.png", nil); rec.Code != 503 {
		t.Fatalf("got %d", rec.Code)
	}
}
