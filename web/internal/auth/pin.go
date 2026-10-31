// Package auth handles joining (PIN / QR secret) and device approval.
package auth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"math/big"
	"sync"
	"time"
)

const (
	ipMaxFailures     = 5
	ipWindow          = 5 * time.Minute
	globalMaxFailures = 20
)

var (
	ErrWrongPIN     = errors.New("wrong PIN")
	ErrIPLocked     = errors.New("too many wrong PINs from this device, wait 5 minutes")
	ErrGlobalLocked = errors.New("PIN locked, ask the laptop owner for a new PIN")
)

// Join holds the per-run PIN and QR join secret, plus brute-force counters.
type Join struct {
	mu       sync.Mutex
	now      func() time.Time
	pin      string
	secret   string
	failures map[string][]time.Time // per IP, within ipWindow
	global   int                    // failures since last Regenerate
}

// NewJoin creates a Join with a fresh PIN and secret. now is injectable for tests.
func NewJoin(now func() time.Time) *Join {
	if now == nil {
		now = time.Now
	}
	j := &Join{now: now}
	j.Regenerate()
	return j
}

// PIN returns the current 4-digit PIN.
func (j *Join) PIN() string { j.mu.Lock(); defer j.mu.Unlock(); return j.pin }

// Secret returns the current QR join secret.
func (j *Join) Secret() string { j.mu.Lock(); defer j.mu.Unlock(); return j.secret }

// Regenerate issues a new PIN and secret and clears all lockouts.
func (j *Join) Regenerate() {
	n, err := rand.Int(rand.Reader, big.NewInt(10000))
	if err != nil {
		panic(err)
	}
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	j.mu.Lock()
	defer j.mu.Unlock()
	j.pin = fmt.Sprintf("%04d", n.Int64())
	j.secret = base64.RawURLEncoding.EncodeToString(b)
	j.failures = map[string][]time.Time{}
	j.global = 0
}

// CheckPIN validates pin for a request coming from ip.
func (j *Join) CheckPIN(ip, pin string) error {
	j.mu.Lock()
	defer j.mu.Unlock()
	if j.global >= globalMaxFailures {
		return ErrGlobalLocked
	}
	now := j.now()
	recent := j.failures[ip][:0]
	for _, t := range j.failures[ip] {
		if now.Sub(t) < ipWindow {
			recent = append(recent, t)
		}
	}
	j.failures[ip] = recent
	if len(recent) >= ipMaxFailures {
		return ErrIPLocked
	}
	if subtle.ConstantTimeCompare([]byte(pin), []byte(j.pin)) == 1 {
		return nil
	}
	j.failures[ip] = append(recent, now)
	j.global++
	return ErrWrongPIN
}

// CheckSecret reports whether s is the current QR join secret.
func (j *Join) CheckSecret(s string) bool {
	j.mu.Lock()
	defer j.mu.Unlock()
	return subtle.ConstantTimeCompare([]byte(s), []byte(j.secret)) == 1
}
