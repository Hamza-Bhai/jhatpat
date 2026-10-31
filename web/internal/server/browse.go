package server

import (
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
)

type browseEntry struct {
	Name  string `json:"name"`
	Path  string `json:"path"`
	IsDir bool   `json:"isDir"`
	Size  int64  `json:"size"`
}

// roots are quick-jump places for the host's file picker.
func roots() []browseEntry {
	var out []browseEntry
	if home, err := os.UserHomeDir(); err == nil {
		for _, sub := range []string{"", "Desktop", "Downloads", "Documents", "Pictures", "Videos", "Music"} {
			p := filepath.Join(home, sub)
			if info, err := os.Stat(p); err == nil && info.IsDir() {
				name := sub
				if name == "" {
					name = "Home"
				}
				out = append(out, browseEntry{Name: name, Path: p, IsDir: true})
			}
		}
	}
	if runtime.GOOS == "windows" {
		for c := 'A'; c <= 'Z'; c++ {
			p := string(c) + `:\`
			if _, err := os.Stat(p); err == nil {
				out = append(out, browseEntry{Name: string(c) + ":", Path: p, IsDir: true})
			}
		}
	} else {
		out = append(out, browseEntry{Name: "/", Path: "/", IsDir: true})
	}
	return out
}

// handleBrowse lists a folder on the laptop so the host can pick files by
// path (browsers never reveal real paths from drag-and-drop).
func (s *Server) handleBrowse(w http.ResponseWriter, r *http.Request) {
	dir := r.URL.Query().Get("dir")
	if dir == "" {
		dir, _ = os.UserHomeDir()
	}
	dir = filepath.Clean(dir)
	des, err := os.ReadDir(dir)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "cannot open folder")
		return
	}
	entries := []browseEntry{}
	for _, de := range des {
		name := de.Name()
		if strings.HasPrefix(name, ".") || strings.HasPrefix(name, "$") {
			continue
		}
		info, err := de.Info()
		if err != nil || info.Mode()&os.ModeSymlink != 0 {
			continue
		}
		entries = append(entries, browseEntry{Name: name, Path: filepath.Join(dir, name), IsDir: de.IsDir(), Size: info.Size()})
	}
	sort.Slice(entries, func(i, j int) bool {
		if entries[i].IsDir != entries[j].IsDir {
			return entries[i].IsDir
		}
		return strings.ToLower(entries[i].Name) < strings.ToLower(entries[j].Name)
	})
	parent := filepath.Dir(dir)
	if parent == dir {
		parent = ""
	}
	writeJSON(w, http.StatusOK, map[string]any{"dir": dir, "parent": parent, "entries": entries, "roots": roots()})
}
