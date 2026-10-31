// Package events pushes live updates to pages with Server-Sent Events.
package events

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"
)

// Event is one SSE message. Types: device-request, devices, item,
// item-removed, progress, doctor, pin, kicked.
type Event struct {
	Type string
	Data any
}

const bufSize = 64

type sub struct {
	audience string
	ch       chan Event
}

// Hub fans events out to subscribers. Senders never block: a full
// subscriber buffer drops the event for that subscriber.
type Hub struct {
	mu   sync.Mutex
	subs map[*sub]struct{}
}

// NewHub returns an empty Hub.
func NewHub() *Hub { return &Hub{subs: map[*sub]struct{}{}} }

// Subscribe registers a listener for audience ("host" or a device ID).
// The returned func unsubscribes.
func (h *Hub) Subscribe(audience string) (<-chan Event, func()) {
	s := &sub{audience: audience, ch: make(chan Event, bufSize)}
	h.mu.Lock()
	h.subs[s] = struct{}{}
	h.mu.Unlock()
	var once sync.Once
	return s.ch, func() {
		once.Do(func() {
			h.mu.Lock()
			delete(h.subs, s)
			h.mu.Unlock()
		})
	}
}

func (h *Hub) deliver(match func(string) bool, e Event) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for s := range h.subs {
		if match(s.audience) {
			select {
			case s.ch <- e:
			default:
			}
		}
	}
}

// Send delivers e to every subscriber of audience.
func (h *Hub) Send(audience string, e Event) {
	h.deliver(func(a string) bool { return a == audience }, e)
}

// Broadcast delivers e to everyone.
func (h *Hub) Broadcast(e Event) { h.deliver(func(string) bool { return true }, e) }

// ServeSSE streams ch to w until the request ends.
func ServeSSE(w http.ResponseWriter, r *http.Request, ch <-chan Event) {
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no")
	flusher, _ := w.(http.Flusher)
	flush := func() {
		if flusher != nil {
			flusher.Flush()
		}
	}
	fmt.Fprint(w, ": connected\n\n")
	flush()
	ping := time.NewTicker(20 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-ping.C:
			fmt.Fprint(w, ": ping\n\n")
			flush()
		case e := <-ch:
			data, err := json.Marshal(e.Data)
			if err != nil {
				continue
			}
			fmt.Fprintf(w, "event: %s\ndata: %s\n\n", e.Type, data)
			flush()
		}
	}
}
