package auth

import (
	"errors"
	"fmt"
	"regexp"
	"testing"
	"time"
)

type fakeClock struct{ t time.Time }

func (c *fakeClock) now() time.Time { return c.t }

func newJoin() (*Join, *fakeClock) {
	c := &fakeClock{t: time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)}
	return NewJoin(c.now), c
}

func wrongPIN(j *Join) string {
	if j.PIN() == "0000" {
		return "1111"
	}
	return "0000"
}

func TestPINIsFourDigits(t *testing.T) {
	j, _ := newJoin()
	if !regexp.MustCompile(`^[0-9]{4}$`).MatchString(j.PIN()) {
		t.Fatalf("pin %q", j.PIN())
	}
}

func TestRegenerateChangesPINAndSecret(t *testing.T) {
	j, _ := newJoin()
	oldSecret := j.Secret()
	changed := false
	for i := 0; i < 20 && !changed; i++ { // PIN may repeat by chance
		oldPIN := j.PIN()
		j.Regenerate()
		changed = j.PIN() != oldPIN
	}
	if !changed || j.Secret() == oldSecret {
		t.Fatal("regenerate did not change pin/secret")
	}
}

func TestIPLockAfterFiveWrong(t *testing.T) {
	j, c := newJoin()
	for i := 0; i < 5; i++ {
		if err := j.CheckPIN("1.1.1.1", wrongPIN(j)); !errors.Is(err, ErrWrongPIN) {
			t.Fatalf("attempt %d: %v", i, err)
		}
	}
	if err := j.CheckPIN("1.1.1.1", j.PIN()); !errors.Is(err, ErrIPLocked) {
		t.Fatalf("want ErrIPLocked, got %v", err)
	}
	c.t = c.t.Add(5*time.Minute + time.Second)
	if err := j.CheckPIN("1.1.1.1", j.PIN()); err != nil {
		t.Fatalf("after lockout window: %v", err)
	}
}

func TestGlobalLockAfterTwenty(t *testing.T) {
	j, _ := newJoin()
	for i := 0; i < 20; i++ {
		_ = j.CheckPIN(fmt.Sprintf("10.0.0.%d", i), wrongPIN(j))
	}
	if err := j.CheckPIN("10.0.1.1", j.PIN()); !errors.Is(err, ErrGlobalLocked) {
		t.Fatalf("want ErrGlobalLocked, got %v", err)
	}
	j.Regenerate()
	if err := j.CheckPIN("10.0.1.1", j.PIN()); err != nil {
		t.Fatalf("after regenerate: %v", err)
	}
}

func TestSecretCompare(t *testing.T) {
	j, _ := newJoin()
	if j.CheckSecret("nope") || !j.CheckSecret(j.Secret()) {
		t.Fatal("secret check wrong")
	}
}
