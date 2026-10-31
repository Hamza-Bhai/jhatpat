package groups

import (
	"os"
	"path/filepath"
	"testing"
)

func TestCreatePersistsAcrossOpen(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	g, err := s.Create("Family")
	if err != nil {
		t.Fatal(err)
	}
	if err := s.SetMembers(g.ID, []string{"p1", "p2"}); err != nil {
		t.Fatal(err)
	}
	s2, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	got, ok := s2.Get(g.ID)
	if !ok || got.Name != "Family" || len(got.Members) != 2 {
		t.Fatalf("got %+v ok=%v", got, ok)
	}
}

func TestDuplicateNameRejected(t *testing.T) {
	s, _ := Open(t.TempDir())
	_, _ = s.Create("Office")
	if _, err := s.Create(" office "); err == nil {
		t.Fatal("duplicate accepted")
	}
}

func TestCorruptFileStartsEmptyAndKeepsBackup(t *testing.T) {
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "groups.json"), []byte("{garbage"), 0o600)
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(s.List()) != 0 {
		t.Fatal("not empty")
	}
	if _, err := os.Stat(filepath.Join(dir, "groups.json.bad")); err != nil {
		t.Fatal("no backup:", err)
	}
}

func TestRememberName(t *testing.T) {
	dir := t.TempDir()
	s, _ := Open(dir)
	if err := s.RememberName("p1", "Mom's Phone"); err != nil {
		t.Fatal(err)
	}
	s2, _ := Open(dir)
	if n, ok := s2.KnownName("p1"); !ok || n != "Mom's Phone" {
		t.Fatalf("got %q %v", n, ok)
	}
}

func TestDeleteAndRename(t *testing.T) {
	s, _ := Open(t.TempDir())
	g, _ := s.Create("A")
	if err := s.Rename(g.ID, "B"); err != nil {
		t.Fatal(err)
	}
	if got, _ := s.Get(g.ID); got.Name != "B" {
		t.Fatal("rename failed")
	}
	if err := s.Delete(g.ID); err != nil {
		t.Fatal(err)
	}
	if _, ok := s.Get(g.ID); ok {
		t.Fatal("not deleted")
	}
}

func TestNamesReturnsCopy(t *testing.T) {
	s, _ := Open(t.TempDir())
	_ = s.RememberName("p1", "A")
	n := s.Names()
	n["p1"] = "changed"
	if got, _ := s.KnownName("p1"); got != "A" || len(s.Names()) != 1 {
		t.Fatal("Names not a copy")
	}
}
