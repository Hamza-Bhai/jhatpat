package auth

import (
	"strings"
	"testing"
)

func TestPendingTokenNotUsable(t *testing.T) {
	r := NewRegistry()
	d := r.Request("Phone", "pid1")
	if _, ok := r.ByToken(d.Token); ok {
		t.Fatal("pending token usable")
	}
}

func TestApproveMakesTokenUsable(t *testing.T) {
	r := NewRegistry()
	d := r.Request("Phone", "pid1")
	if err := r.Approve(d.ID); err != nil {
		t.Fatal(err)
	}
	got, ok := r.ByToken(d.Token)
	if !ok || got.ID != d.ID || got.State != Approved {
		t.Fatalf("got %+v ok=%v", got, ok)
	}
}

func TestBlockPreventsReRequest(t *testing.T) {
	r := NewRegistry()
	d := r.Request("Phone", "pid1")
	if err := r.Block(d.ID); err != nil {
		t.Fatal(err)
	}
	if !r.IsBlocked("pid1") {
		t.Fatal("pid not blocked")
	}
	if _, ok := r.ByToken(d.Token); ok {
		t.Fatal("blocked token usable")
	}
}

func TestUnblockLetsTheDeviceAskAgain(t *testing.T) {
	r := NewRegistry()
	d := r.Request("Phone", "pid1")
	_ = r.Block(d.ID)
	if err := r.Unblock(d.ID); err != nil {
		t.Fatal(err)
	}
	if r.IsBlocked("pid1") {
		t.Fatal("pid still blocked")
	}
	if _, ok := r.Get(d.ID); ok {
		t.Fatal("unblocked device still listed")
	}
	if _, ok := r.ByToken(d.Token); ok {
		t.Fatal("old token usable without a new approval")
	}
}

func TestUnblockOnlyAppliesToBlockedDevices(t *testing.T) {
	r := NewRegistry()
	d := r.Request("Phone", "pid1")
	_ = r.Approve(d.ID)
	if err := r.Unblock(d.ID); err == nil {
		t.Fatal("unblocking an approved device must fail")
	}
	if got, ok := r.Get(d.ID); !ok || got.State != Approved {
		t.Fatal("approved device was changed")
	}
	if err := r.Unblock("nope"); err != ErrNoDevice {
		t.Fatalf("got %v", err)
	}
}

func TestKickRevokesToken(t *testing.T) {
	r := NewRegistry()
	d := r.Request("Phone", "pid1")
	_ = r.Approve(d.ID)
	if err := r.Kick(d.ID); err != nil {
		t.Fatal(err)
	}
	if _, ok := r.ByToken(d.Token); ok {
		t.Fatal("kicked token usable")
	}
	if _, ok := r.Get(d.ID); ok {
		t.Fatal("kicked device still listed")
	}
}

func TestRenameTrimsAndLimits(t *testing.T) {
	r := NewRegistry()
	d := r.Request("Phone", "pid1")
	if err := r.Rename(d.ID, "   "); err == nil {
		t.Fatal("empty name accepted")
	}
	_ = r.Rename(d.ID, "  "+strings.Repeat("é", 50)+"  ")
	got, _ := r.Get(d.ID)
	if n := len([]rune(got.Name)); n != 40 {
		t.Fatalf("name runes %d", n)
	}
}

func TestLookupFindsAnyState(t *testing.T) {
	r := NewRegistry()
	d := r.Request("Phone", "pid1")
	got, ok := r.Lookup(d.Token)
	if !ok || got.State != Pending {
		t.Fatal("pending not found")
	}
	if _, ok := r.Lookup("nope"); ok {
		t.Fatal("bogus found")
	}
}

func TestRequestNameFallback(t *testing.T) {
	r := NewRegistry()
	d := r.Request("", "pid1")
	if d.Name == "" {
		t.Fatal("empty name")
	}
}
