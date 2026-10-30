package transfer

import (
	"archive/zip"
	"bytes"
	"io"
	"os"
	"path/filepath"
	"sort"
	"testing"
)

func TestZipContainsTree(t *testing.T) {
	root := filepath.Join(t.TempDir(), "Trip")
	_ = os.MkdirAll(filepath.Join(root, "sub"), 0o755)
	_ = os.WriteFile(filepath.Join(root, "a.txt"), []byte("aaa"), 0o644)
	_ = os.WriteFile(filepath.Join(root, "sub", "b.txt"), []byte("bbb"), 0o644)
	var buf bytes.Buffer
	if err := WriteZip(&buf, root); err != nil {
		t.Fatal(err)
	}
	zr, err := zip.NewReader(bytes.NewReader(buf.Bytes()), int64(buf.Len()))
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, f := range zr.File {
		names = append(names, f.Name)
		if f.Method != zip.Store {
			t.Errorf("%s method %d", f.Name, f.Method)
		}
		rc, _ := f.Open()
		b, _ := io.ReadAll(rc)
		rc.Close()
		if string(b) != string(f.Name[len(f.Name)-5])+string(f.Name[len(f.Name)-5])+string(f.Name[len(f.Name)-5]) {
			t.Errorf("%s content %q", f.Name, b)
		}
	}
	sort.Strings(names)
	if len(names) != 2 || names[0] != "Trip/a.txt" || names[1] != "Trip/sub/b.txt" {
		t.Fatalf("names %v", names)
	}
}

func TestZipSkipsSymlink(t *testing.T) {
	root := filepath.Join(t.TempDir(), "R")
	_ = os.MkdirAll(root, 0o755)
	_ = os.WriteFile(filepath.Join(root, "a.txt"), []byte("aaa"), 0o644)
	if err := os.Symlink(filepath.Join(root, "a.txt"), filepath.Join(root, "link.txt")); err != nil {
		t.Skip("symlinks unavailable:", err)
	}
	var buf bytes.Buffer
	if err := WriteZip(&buf, root); err != nil {
		t.Fatal(err)
	}
	zr, _ := zip.NewReader(bytes.NewReader(buf.Bytes()), int64(buf.Len()))
	if len(zr.File) != 1 {
		t.Fatalf("got %d entries", len(zr.File))
	}
}
