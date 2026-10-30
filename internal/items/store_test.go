package items

import "testing"

func TestDeviceSeesOnlyAddressed(t *testing.T) {
	s := NewStore()
	it := s.Add(Item{Kind: Text, Text: "hi", From: "host", To: []string{"A"}})
	if len(s.Visible("B")) != 0 {
		t.Fatal("B sees item")
	}
	if _, ok := s.GetFor("B", it.ID); ok {
		t.Fatal("B can get item")
	}
	if _, ok := s.GetFor("A", it.ID); !ok {
		t.Fatal("A cannot get item")
	}
}

func TestSenderSeesOwnItem(t *testing.T) {
	s := NewStore()
	it := s.Add(Item{Kind: Text, Text: "hi", From: "A", To: []string{"host"}})
	if _, ok := s.GetFor("A", it.ID); !ok {
		t.Fatal("sender cannot see own item")
	}
	if len(s.Visible("host")) != 1 {
		t.Fatal("host does not see item")
	}
}

func TestGroupResolvedListSeesAll(t *testing.T) {
	s := NewStore()
	s.Add(Item{Kind: Text, Text: "x", From: "host", To: []string{"A", "B"}})
	if len(s.Visible("A")) != 1 || len(s.Visible("B")) != 1 || len(s.Visible("C")) != 0 {
		t.Fatal("wrong visibility")
	}
}

func TestRemoveHides(t *testing.T) {
	s := NewStore()
	it := s.Add(Item{Kind: Text, Text: "x", From: "host", To: []string{"A"}})
	s.Remove(it.ID)
	if len(s.Visible("A")) != 0 || len(s.All()) != 0 {
		t.Fatal("still visible")
	}
}

func TestVisibleNewestFirstAndIDsUnique(t *testing.T) {
	s := NewStore()
	a := s.Add(Item{Kind: Text, Text: "1", From: "host", To: []string{"A"}})
	b := s.Add(Item{Kind: Text, Text: "2", From: "host", To: []string{"A"}})
	v := s.Visible("A")
	if a.ID == b.ID || v[0].ID != b.ID {
		t.Fatalf("order/ids wrong: %v", v)
	}
}
