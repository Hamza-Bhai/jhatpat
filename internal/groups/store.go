// Package groups persists named groups of devices and remembered device names.
package groups

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"github.com/Hamza-Bhai/jhatpat/internal/auth"
)

var (
	ErrNotFound  = errors.New("group not found")
	ErrDuplicate = errors.New("a group with this name already exists")
	ErrEmptyName = errors.New("group name is empty")
)

// Group is a named set of devices, identified by their PersistentID.
type Group struct {
	ID      string   `json:"id"`
	Name    string   `json:"name"`
	Members []string `json:"members"`
}

type fileData struct {
	Groups []Group           `json:"groups"`
	Names  map[string]string `json:"names"`
}

// Store keeps groups and known device names in <configDir>/groups.json.
type Store struct {
	mu   sync.Mutex
	path string
	data fileData
}

// Open loads the store; a corrupt file is moved to groups.json.bad and an empty store is used.
func Open(configDir string) (*Store, error) {
	if err := os.MkdirAll(configDir, 0o700); err != nil {
		return nil, err
	}
	s := &Store{path: filepath.Join(configDir, "groups.json")}
	raw, err := os.ReadFile(s.path)
	switch {
	case errors.Is(err, os.ErrNotExist):
	case err != nil:
		return nil, err
	default:
		if json.Unmarshal(raw, &s.data) != nil {
			_ = os.Rename(s.path, s.path+".bad")
			s.data = fileData{}
		}
	}
	if s.data.Names == nil {
		s.data.Names = map[string]string{}
	}
	return s, nil
}

func (s *Store) save() error {
	raw, err := json.MarshalIndent(s.data, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}

func (s *Store) find(id string) int {
	for i, g := range s.data.Groups {
		if g.ID == id {
			return i
		}
	}
	return -1
}

func (s *Store) nameTaken(name, exceptID string) bool {
	for _, g := range s.data.Groups {
		if g.ID != exceptID && strings.EqualFold(g.Name, name) {
			return true
		}
	}
	return false
}

// Create adds a new empty group.
func (s *Store) Create(name string) (Group, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return Group{}, ErrEmptyName
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.nameTaken(name, "") {
		return Group{}, ErrDuplicate
	}
	g := Group{ID: auth.RandomID(9), Name: name, Members: []string{}}
	s.data.Groups = append(s.data.Groups, g)
	return g, s.save()
}

// Rename changes a group's name.
func (s *Store) Rename(id, name string) error {
	name = strings.TrimSpace(name)
	if name == "" {
		return ErrEmptyName
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.find(id)
	if i < 0 {
		return ErrNotFound
	}
	if s.nameTaken(name, id) {
		return ErrDuplicate
	}
	s.data.Groups[i].Name = name
	return s.save()
}

// Delete removes a group.
func (s *Store) Delete(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.find(id)
	if i < 0 {
		return ErrNotFound
	}
	s.data.Groups = append(s.data.Groups[:i], s.data.Groups[i+1:]...)
	return s.save()
}

// SetMembers replaces a group's member list.
func (s *Store) SetMembers(id string, persistentIDs []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.find(id)
	if i < 0 {
		return ErrNotFound
	}
	s.data.Groups[i].Members = append([]string{}, persistentIDs...)
	return s.save()
}

// List returns all groups sorted by name.
func (s *Store) List() []Group {
	s.mu.Lock()
	out := make([]Group, len(s.data.Groups))
	for i, g := range s.data.Groups {
		g.Members = append([]string{}, g.Members...)
		out[i] = g
	}
	s.mu.Unlock()
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return out
}

// Get returns one group.
func (s *Store) Get(id string) (Group, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	i := s.find(id)
	if i < 0 {
		return Group{}, false
	}
	g := s.data.Groups[i]
	g.Members = append([]string{}, g.Members...)
	return g, true
}

// RememberName stores the last name used by a device.
func (s *Store) RememberName(persistentID, name string) error {
	if persistentID == "" {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data.Names[persistentID] = name
	return s.save()
}

// KnownName returns the remembered name for a device.
func (s *Store) KnownName(persistentID string) (string, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	n, ok := s.data.Names[persistentID]
	return n, ok
}

// Names returns a copy of all remembered device names keyed by PersistentID.
func (s *Store) Names() map[string]string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make(map[string]string, len(s.data.Names))
	for k, v := range s.data.Names {
		out[k] = v
	}
	return out
}
