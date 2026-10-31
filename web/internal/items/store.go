// Package items keeps the files, folders and texts being shared, and decides
// who may see each one.
package items

import (
	"slices"
	"sort"
	"sync"
	"time"

	"github.com/Hamza-Bhai/jhatpat/web/internal/auth"
)

// Kind is what an item carries.
type Kind string

const (
	File   Kind = "file"
	Folder Kind = "folder"
	Text   Kind = "text"
)

// Host is the recipient/sender id used for the laptop owner.
const Host = "host"

// Item is one shared thing. Path is never sent to clients.
type Item struct {
	ID        string    `json:"id"`
	Kind      Kind      `json:"kind"`
	Name      string    `json:"name"`
	Size      int64     `json:"size"`
	Text      string    `json:"text,omitempty"`
	Path      string    `json:"-"`
	From      string    `json:"from"`
	To        []string  `json:"to"`
	SHA256    string    `json:"sha256,omitempty"`
	CreatedAt time.Time `json:"createdAt"`
	// Moved is set when the host's app moved the file out of JhatPat's folder
	// (for example into the Android Gallery); MovedTo says where, for display.
	Moved   bool   `json:"moved"`
	MovedTo string `json:"movedTo,omitempty"`
}

// Store holds items in memory for the current run.
type Store struct {
	mu    sync.Mutex
	items map[string]Item
	seq   map[string]int // insertion order, for stable newest-first sorting
	n     int
}

// NewStore returns an empty Store.
func NewStore() *Store { return &Store{items: map[string]Item{}, seq: map[string]int{}} }

// Add stores it with a fresh random ID and timestamp.
func (s *Store) Add(it Item) Item {
	it.ID = auth.RandomID(12)
	it.CreatedAt = time.Now()
	it.To = append([]string{}, it.To...)
	s.mu.Lock()
	defer s.mu.Unlock()
	s.n++
	s.items[it.ID] = it
	s.seq[it.ID] = s.n
	return it
}

func canSee(it Item, viewer string) bool {
	return it.From == viewer || slices.Contains(it.To, viewer)
}

func (s *Store) sorted(keep func(Item) bool) []Item {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := []Item{}
	for _, it := range s.items {
		if keep(it) {
			out = append(out, it)
		}
	}
	sort.Slice(out, func(i, j int) bool { return s.seq[out[i].ID] > s.seq[out[j].ID] })
	return out
}

// Visible returns items sent by or addressed to viewer, newest first.
func (s *Store) Visible(viewer string) []Item {
	return s.sorted(func(it Item) bool { return canSee(it, viewer) })
}

// GetFor returns an item only if viewer may see it.
func (s *Store) GetFor(viewer, id string) (Item, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	it, ok := s.items[id]
	if !ok || !canSee(it, viewer) {
		return Item{}, false
	}
	return it, true
}

// Get returns any item (host view).
func (s *Store) Get(id string) (Item, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	it, ok := s.items[id]
	return it, ok
}

// MarkMoved records that the item's file now lives elsewhere. The old path is
// forgotten. It reports false for an unknown item.
func (s *Store) MarkMoved(id, where string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	it, ok := s.items[id]
	if !ok {
		return false
	}
	it.Moved, it.MovedTo, it.Path = true, where, ""
	s.items[id] = it
	return true
}

// Remove deletes an item.
func (s *Store) Remove(id string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.items, id)
	delete(s.seq, id)
}

// All returns every item, newest first (host view).
func (s *Store) All() []Item { return s.sorted(func(Item) bool { return true }) }
