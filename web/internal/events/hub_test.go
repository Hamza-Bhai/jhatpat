package events

import (
	"context"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func recv(ch <-chan Event) (Event, bool) {
	select {
	case e := <-ch:
		return e, true
	case <-time.After(100 * time.Millisecond):
		return Event{}, false
	}
}

func TestSendOnlyToAudience(t *testing.T) {
	h := NewHub()
	a, ca := h.Subscribe("A")
	defer ca()
	b, cb := h.Subscribe("B")
	defer cb()
	h.Send("A", Event{Type: "item"})
	if _, ok := recv(a); !ok {
		t.Fatal("A got nothing")
	}
	if _, ok := recv(b); ok {
		t.Fatal("B got A's event")
	}
}

func TestBroadcastReachesAll(t *testing.T) {
	h := NewHub()
	a, ca := h.Subscribe("A")
	defer ca()
	b, cb := h.Subscribe("host")
	defer cb()
	h.Broadcast(Event{Type: "doctor"})
	_, okA := recv(a)
	_, okB := recv(b)
	if !okA || !okB {
		t.Fatal("broadcast missed")
	}
}

func TestUnsubscribeStopsDelivery(t *testing.T) {
	h := NewHub()
	a, ca := h.Subscribe("A")
	ca()
	h.Send("A", Event{Type: "item"})
	if e, ok := recv(a); ok && e.Type != "" {
		t.Fatal("delivered after unsubscribe")
	}
}

func TestSlowSubscriberDoesNotBlock(t *testing.T) {
	h := NewHub()
	_, c := h.Subscribe("A")
	defer c()
	done := make(chan struct{})
	go func() {
		for i := 0; i < 1000; i++ {
			h.Send("A", Event{Type: "progress"})
		}
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("send blocked")
	}
}

func TestServeSSEFormat(t *testing.T) {
	ch := make(chan Event, 1)
	ch <- Event{Type: "item", Data: map[string]string{"id": "x"}}
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	rec := httptest.NewRecorder()
	ServeSSE(rec, httptest.NewRequest("GET", "/", nil).WithContext(ctx), ch)
	body := rec.Body.String()
	if !strings.Contains(body, "event: item\n") || !strings.Contains(body, `data: {"id":"x"}`) {
		t.Fatalf("body %q", body)
	}
	if rec.Header().Get("Content-Type") != "text/event-stream" {
		t.Fatal("content type")
	}
}
