package auth

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"sort"
	"strings"
	"sync"
	"time"
)

// DeviceState is where a device is in the approval flow.
type DeviceState int

const (
	Pending DeviceState = iota
	Approved
	Blocked
)

func (s DeviceState) String() string {
	return [...]string{"pending", "approved", "blocked"}[s]
}

const maxNameRunes = 40

var (
	ErrNoDevice  = errors.New("device not found")
	ErrEmptyName = errors.New("name is empty")
)

// Device is one phone/computer that asked to join.
type Device struct {
	ID           string
	Name         string
	PersistentID string // stable id the browser remembers across runs
	Token        string // secret cookie value, usable only once Approved
	State        DeviceState
	JoinedAt     time.Time
}

// Registry tracks devices for the current run. Nothing here is persisted:
// every device must be approved again on every run.
type Registry struct {
	mu         sync.Mutex
	byID       map[string]*Device
	byToken    map[string]*Device
	blockedPID map[string]bool
}

// NewRegistry returns an empty Registry.
func NewRegistry() *Registry {
	return &Registry{byID: map[string]*Device{}, byToken: map[string]*Device{}, blockedPID: map[string]bool{}}
}

// RandomID returns n random bytes encoded as base64url.
func RandomID(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

func cleanName(name string) string {
	r := []rune(strings.TrimSpace(name))
	if len(r) > maxNameRunes {
		r = r[:maxNameRunes]
	}
	return strings.TrimSpace(string(r))
}

// Request registers a new Pending device.
func (r *Registry) Request(name, persistentID string) *Device {
	if name = cleanName(name); name == "" {
		name = "Device"
	}
	d := &Device{ID: RandomID(9), Name: name, PersistentID: persistentID, Token: RandomID(32), State: Pending, JoinedAt: time.Now()}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.byID[d.ID] = d
	r.byToken[d.Token] = d
	cp := *d
	return &cp
}

func (r *Registry) setState(id string, s DeviceState) (*Device, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	d, ok := r.byID[id]
	if !ok {
		return nil, ErrNoDevice
	}
	d.State = s
	return d, nil
}

// Approve lets a pending device in.
func (r *Registry) Approve(id string) error { _, err := r.setState(id, Approved); return err }

// Block rejects a device and its PersistentID for the rest of this run.
func (r *Registry) Block(id string) error {
	d, err := r.setState(id, Blocked)
	if err == nil && d.PersistentID != "" {
		r.mu.Lock()
		r.blockedPID[d.PersistentID] = true
		r.mu.Unlock()
	}
	return err
}

// Kick removes a device; it may ask to join again.
func (r *Registry) Kick(id string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	d, ok := r.byID[id]
	if !ok {
		return ErrNoDevice
	}
	delete(r.byID, id)
	delete(r.byToken, d.Token)
	return nil
}

// Rename changes a device's display name.
func (r *Registry) Rename(id, name string) error {
	if name = cleanName(name); name == "" {
		return ErrEmptyName
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	d, ok := r.byID[id]
	if !ok {
		return ErrNoDevice
	}
	d.Name = name
	return nil
}

// ByToken finds an Approved device by its token.
func (r *Registry) ByToken(token string) (*Device, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	d, ok := r.byToken[token]
	if !ok || d.State != Approved {
		return nil, false
	}
	cp := *d
	return &cp, true
}

// Get returns a device in any state.
func (r *Registry) Get(id string) (*Device, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	d, ok := r.byID[id]
	if !ok {
		return nil, false
	}
	cp := *d
	return &cp, true
}

// List returns all devices, oldest first.
func (r *Registry) List() []Device {
	r.mu.Lock()
	out := make([]Device, 0, len(r.byID))
	for _, d := range r.byID {
		out = append(out, *d)
	}
	r.mu.Unlock()
	sort.Slice(out, func(i, j int) bool { return out[i].JoinedAt.Before(out[j].JoinedAt) })
	return out
}

// IsBlocked reports whether persistentID was blocked during this run.
func (r *Registry) IsBlocked(persistentID string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return persistentID != "" && r.blockedPID[persistentID]
}
