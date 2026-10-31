package transfer

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const mib = 1 << 20

func payload(n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = byte(i * 7)
	}
	return b
}

func TestUploadTwoChunksAndFinish(t *testing.T) {
	dir := t.TempDir()
	u := NewUploads(dir)
	data := payload(10 * mib)
	id, err := u.Start("A", "video.mp4", int64(len(data)))
	if err != nil {
		t.Fatal(err)
	}
	if n, err := u.WriteChunk("A", id, 0, bytes.NewReader(data[:8*mib])); err != nil || n != 8*mib {
		t.Fatal(n, err)
	}
	if _, err := u.WriteChunk("A", id, 8*mib, bytes.NewReader(data[8*mib:])); err != nil {
		t.Fatal(err)
	}
	path, sum, err := u.Finish("A", id)
	if err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(path)
	want := sha256.Sum256(data)
	if !bytes.Equal(got, data) || sum != hex.EncodeToString(want[:]) || filepath.Base(path) != "video.mp4" {
		t.Fatalf("path %s sum %s", path, sum)
	}
}

func TestResumeFromOffset(t *testing.T) {
	u := NewUploads(t.TempDir())
	data := payload(10 * mib)
	id, _ := u.Start("A", "f.bin", int64(len(data)))
	_, _ = u.WriteChunk("A", id, 0, bytes.NewReader(data[:8*mib]))
	off, err := u.Offset("A", id)
	if err != nil || off != 8*mib {
		t.Fatal(off, err)
	}
	_, _ = u.WriteChunk("A", id, off, bytes.NewReader(data[off:]))
	if _, _, err := u.Finish("A", id); err != nil {
		t.Fatal(err)
	}
}

func TestWrongOffsetRejected(t *testing.T) {
	u := NewUploads(t.TempDir())
	id, _ := u.Start("A", "f.bin", 10*mib)
	_, _ = u.WriteChunk("A", id, 0, bytes.NewReader(payload(8*mib)))
	if _, err := u.WriteChunk("A", id, 0, bytes.NewReader(payload(10))); !errors.Is(err, ErrOffsetMismatch) {
		t.Fatalf("got %v", err)
	}
}

func TestChunkTooLargeRejected(t *testing.T) {
	u := NewUploads(t.TempDir())
	id, _ := u.Start("A", "f.bin", 20*mib)
	if _, err := u.WriteChunk("A", id, 0, bytes.NewReader(payload(9*mib))); !errors.Is(err, ErrChunkTooLarge) {
		t.Fatalf("got %v", err)
	}
	if off, _ := u.Offset("A", id); off != 0 {
		t.Fatalf("offset advanced to %d", off)
	}
}

func TestOtherOwnerCannotWrite(t *testing.T) {
	u := NewUploads(t.TempDir())
	id, _ := u.Start("A", "f.bin", 10)
	if _, err := u.WriteChunk("B", id, 0, bytes.NewReader(payload(10))); !errors.Is(err, ErrNotFound) {
		t.Fatalf("got %v", err)
	}
}

type failingReader struct{ n int }

func (f *failingReader) Read(p []byte) (int, error) {
	if f.n <= 0 {
		return 0, errors.New("disk on fire")
	}
	k := min(len(p), f.n)
	f.n -= k
	return k, nil
}

func TestWriteErrorKeepsNoFinalFile(t *testing.T) {
	dir := t.TempDir()
	u := NewUploads(dir)
	id, _ := u.Start("A", "f.bin", 4096)
	if _, err := u.WriteChunk("A", id, 0, &failingReader{n: 1024}); err == nil {
		t.Fatal("no error")
	}
	if _, err := os.Stat(filepath.Join(dir, "f.bin")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("final file exists")
	}
	if off, _ := u.Offset("A", id); off != 0 {
		t.Fatalf("failed chunk counted: %d", off)
	}
	if _, _, err := u.Finish("A", id); !errors.Is(err, ErrSizeMismatch) {
		t.Fatalf("got %v", err)
	}
}

func TestFinishSizeMismatch(t *testing.T) {
	u := NewUploads(t.TempDir())
	id, _ := u.Start("A", "f.bin", 100)
	_, _ = u.WriteChunk("A", id, 0, bytes.NewReader(payload(50)))
	if _, _, err := u.Finish("A", id); !errors.Is(err, ErrSizeMismatch) {
		t.Fatalf("got %v", err)
	}
}

func TestOverflowBeyondSizeRejected(t *testing.T) {
	u := NewUploads(t.TempDir())
	id, _ := u.Start("A", "f.bin", 10)
	if _, err := u.WriteChunk("A", id, 0, bytes.NewReader(payload(20))); err == nil {
		t.Fatal("accepted more than declared size")
	}
}

func TestFolderUploadKeepsRelPath(t *testing.T) {
	dir := t.TempDir()
	u := NewUploads(dir)
	id, err := u.Start("A", "Trip/day1/a.jpg", 3)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = u.WriteChunk("A", id, 0, bytes.NewReader([]byte("abc")))
	p, _, err := u.Finish("A", id)
	if err != nil || p != filepath.Join(dir, "Trip", "day1", "a.jpg") {
		t.Fatalf("got %s %v", p, err)
	}
}

func TestAbortRemovesPart(t *testing.T) {
	dir := t.TempDir()
	u := NewUploads(dir)
	id, _ := u.Start("A", "f.bin", 10)
	if err := u.Abort("A", id); err != nil {
		t.Fatal(err)
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 0 {
		t.Fatalf("left %v", entries)
	}
}

var _ = io.EOF

func TestActiveListsUnfinishedUploadsWithProgress(t *testing.T) {
	u := NewUploads(t.TempDir())
	a, err := u.Start("d1", "movie.mp4", 10)
	if err != nil {
		t.Fatal(err)
	}
	b, err := u.Start("d2", "notes.txt", 4)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := u.WriteChunk("d1", a, 0, strings.NewReader("abcd")); err != nil {
		t.Fatal(err)
	}
	got := u.Active()
	if len(got) != 2 {
		t.Fatalf("active = %+v", got)
	}
	byName := map[string]Progress{}
	for _, p := range got {
		byName[p.Name] = p
	}
	if p := byName["movie.mp4"]; p.Owner != "d1" || p.Size != 10 || p.Received != 4 {
		t.Fatalf("movie.mp4 = %+v", p)
	}
	if p := byName["notes.txt"]; p.Owner != "d2" || p.Size != 4 || p.Received != 0 {
		t.Fatalf("notes.txt = %+v", p)
	}
	// finished and aborted uploads leave the list
	if err := u.Abort("d2", b); err != nil {
		t.Fatal(err)
	}
	if _, err := u.WriteChunk("d1", a, 4, strings.NewReader("efghij")); err != nil {
		t.Fatal(err)
	}
	if _, _, err := u.Finish("d1", a); err != nil {
		t.Fatal(err)
	}
	if left := u.Active(); len(left) != 0 {
		t.Fatalf("still active: %+v", left)
	}
}

func TestActiveIsSortedForStableOutput(t *testing.T) {
	u := NewUploads(t.TempDir())
	for _, n := range []string{"c.bin", "a.bin", "b.bin"} {
		if _, err := u.Start("d", n, 1); err != nil {
			t.Fatal(err)
		}
	}
	var names []string
	for _, p := range u.Active() {
		names = append(names, p.Name)
	}
	if strings.Join(names, ",") != "a.bin,b.bin,c.bin" {
		t.Fatalf("order = %v", names)
	}
}
