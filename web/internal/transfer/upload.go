package transfer

import (
	"crypto/sha256"
	"encoding"
	"encoding/hex"
	"errors"
	"hash"
	"io"
	"os"
	"path/filepath"
	"sync"

	"github.com/Hamza-Bhai/jhatpat/web/internal/auth"
)

// ChunkSize is the largest body accepted by one WriteChunk call.
const ChunkSize = 8 << 20

var (
	ErrNotFound       = errors.New("upload not found")
	ErrOffsetMismatch = errors.New("offset does not match bytes received")
	ErrChunkTooLarge  = errors.New("chunk too large")
	ErrSizeMismatch   = errors.New("received size does not match declared size")
	ErrTooMuchData    = errors.New("more data than declared size")
)

type session struct {
	mu        sync.Mutex
	owner     string
	finalPath string
	size      int64
	received  int64
	hash      hash.Hash
}

// Uploads manages resumable, chunked uploads into a save folder.
type Uploads struct {
	dir      string
	mu       sync.Mutex
	sessions map[string]*session
}

// NewUploads stores finished files under saveDir.
func NewUploads(saveDir string) *Uploads {
	return &Uploads{dir: saveDir, sessions: map[string]*session{}}
}

// Start opens an upload of size bytes for relPath (a file name or a
// folder-relative path) owned by owner, and returns its id.
func (u *Uploads) Start(owner, relPath string, size int64) (string, error) {
	if size < 0 {
		return "", ErrSizeMismatch
	}
	rel, err := SanitizeRelPath(relPath)
	if err != nil {
		return "", err
	}
	parent := filepath.Join(u.dir, filepath.Dir(rel))
	if err := os.MkdirAll(parent, 0o755); err != nil {
		return "", err
	}
	final, err := UniquePath(parent, filepath.Base(rel))
	if err != nil {
		return "", err
	}
	id := auth.RandomID(12)
	u.mu.Lock()
	u.sessions[id] = &session{owner: owner, finalPath: final, size: size, hash: sha256.New()}
	u.mu.Unlock()
	return id, nil
}

func (u *Uploads) get(owner, id string) (*session, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	s, ok := u.sessions[id]
	if !ok || s.owner != owner {
		return nil, ErrNotFound
	}
	return s, nil
}

// Offset returns how many bytes have been received so far.
func (u *Uploads) Offset(owner, id string) (int64, error) {
	s, err := u.get(owner, id)
	if err != nil {
		return 0, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.received, nil
}

// WriteChunk appends r at offset, which must equal the bytes received so far.
// A failed chunk leaves the received count (and hash) unchanged.
func (u *Uploads) WriteChunk(owner, id string, offset int64, r io.Reader) (int64, error) {
	s, err := u.get(owner, id)
	if err != nil {
		return 0, err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if offset != s.received {
		return s.received, ErrOffsetMismatch
	}
	limit := min(int64(ChunkSize), s.size-s.received)
	f, err := os.OpenFile(s.finalPath+PartSuffix, os.O_WRONLY, 0)
	if err != nil {
		return s.received, err
	}
	defer f.Close()
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		return s.received, err
	}
	snapshot, err := s.hash.(encoding.BinaryMarshaler).MarshalBinary()
	if err != nil {
		return s.received, err
	}
	n, err := io.Copy(io.MultiWriter(f, s.hash), io.LimitReader(r, limit+1))
	if err == nil && n > limit {
		if limit == ChunkSize {
			err = ErrChunkTooLarge
		} else {
			err = ErrTooMuchData
		}
	}
	if err != nil {
		_ = s.hash.(encoding.BinaryUnmarshaler).UnmarshalBinary(snapshot)
		return s.received, err
	}
	s.received += n
	return s.received, nil
}

// Finish checks the size, moves the file into place and returns its path
// and SHA-256.
func (u *Uploads) Finish(owner, id string) (string, string, error) {
	s, err := u.get(owner, id)
	if err != nil {
		return "", "", err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.received != s.size {
		return "", "", ErrSizeMismatch
	}
	part := s.finalPath + PartSuffix
	if err := os.Truncate(part, s.size); err != nil {
		return "", "", err
	}
	final := s.finalPath
	if _, err := os.Lstat(final); err == nil { // taken meanwhile by something else
		alt, err := UniquePath(filepath.Dir(final), filepath.Base(final))
		if err != nil {
			return "", "", err
		}
		_ = os.Remove(alt + PartSuffix)
		final = alt
	}
	if err := os.Rename(part, final); err != nil {
		return "", "", err
	}
	u.mu.Lock()
	delete(u.sessions, id)
	u.mu.Unlock()
	return final, hex.EncodeToString(s.hash.Sum(nil)), nil
}

// Abort cancels an upload and deletes its partial file.
func (u *Uploads) Abort(owner, id string) error {
	s, err := u.get(owner, id)
	if err != nil {
		return err
	}
	u.mu.Lock()
	delete(u.sessions, id)
	u.mu.Unlock()
	return os.Remove(s.finalPath + PartSuffix)
}

// AbortAll deletes every unfinished partial file (used on shutdown).
func (u *Uploads) AbortAll() {
	u.mu.Lock()
	defer u.mu.Unlock()
	for id, s := range u.sessions {
		_ = os.Remove(s.finalPath + PartSuffix)
		delete(u.sessions, id)
	}
}
