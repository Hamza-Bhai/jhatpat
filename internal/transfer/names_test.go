package transfer

import (
	"path/filepath"
	"sync"
	"testing"
)

func TestSanitizeTraversal(t *testing.T) {
	if got := SanitizeName("../../evil.txt"); got != "evil.txt" {
		t.Fatalf("got %q", got)
	}
	if got := SanitizeName(`..\..\evil.txt`); got != "evil.txt" {
		t.Fatalf("got %q", got)
	}
}

func TestSanitizeWindowsReserved(t *testing.T) {
	cases := map[string]string{"CON.txt": "_CON.txt", "nul": "_nul", "a:b?.jpg": "a_b_.jpg", "x. ": "x", "": "file", "..": "file"}
	for in, want := range cases {
		if got := SanitizeName(in); got != want {
			t.Errorf("%q → %q, want %q", in, got, want)
		}
	}
}

func TestSanitizeKeepsEmoji(t *testing.T) {
	if got := SanitizeName("🎉 party.mp4"); got != "🎉 party.mp4" {
		t.Fatalf("got %q", got)
	}
}

func TestSanitizeLongKeepsExtension(t *testing.T) {
	long := ""
	for i := 0; i < 300; i++ {
		long += "a"
	}
	got := SanitizeName(long + ".jpg")
	if len(got) > 200 || filepath.Ext(got) != ".jpg" {
		t.Fatalf("len %d ext %q", len(got), filepath.Ext(got))
	}
}

func TestSanitizeRelPathRejectsDotDot(t *testing.T) {
	if _, err := SanitizeRelPath("a/../b"); err == nil {
		t.Fatal("accepted ..")
	}
	if _, err := SanitizeRelPath("/etc/passwd"); err == nil {
		t.Fatal("accepted absolute")
	}
	got, err := SanitizeRelPath("Photos/CON/a?.jpg")
	if err != nil || got != filepath.Join("Photos", "_CON", "a_.jpg") {
		t.Fatalf("got %q %v", got, err)
	}
}

func TestUniquePathConcurrent(t *testing.T) {
	dir := t.TempDir()
	var mu sync.Mutex
	seen := map[string]bool{}
	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			p, err := UniquePath(dir, "photo.jpg")
			if err != nil {
				t.Error(err)
				return
			}
			mu.Lock()
			seen[p] = true
			mu.Unlock()
		}()
	}
	wg.Wait()
	if len(seen) != 10 || !seen[filepath.Join(dir, "photo (1).jpg")] {
		t.Fatalf("got %v", seen)
	}
}
