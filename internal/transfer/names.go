// Package transfer moves bytes between disk and the network safely.
package transfer

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf8"
)

const maxNameBytes = 200

// PartSuffix marks a file that is still being uploaded.
const PartSuffix = ".part"

var ErrBadPath = errors.New("invalid path")

var reserved = map[string]bool{"CON": true, "PRN": true, "AUX": true, "NUL": true}

func init() {
	for i := 1; i <= 9; i++ {
		reserved[fmt.Sprintf("COM%d", i)] = true
		reserved[fmt.Sprintf("LPT%d", i)] = true
	}
}

// SanitizeName turns any client-supplied name into a safe single file name
// that is valid on Windows, macOS and Linux.
func SanitizeName(name string) string {
	if i := strings.LastIndexAny(name, `/\`); i >= 0 {
		name = name[i+1:]
	}
	var b strings.Builder
	for _, r := range name {
		switch {
		case r < 32 || r == 127:
		case strings.ContainsRune(`<>:"|?*`, r):
			b.WriteRune('_')
		default:
			b.WriteRune(r)
		}
	}
	name = strings.TrimRight(strings.TrimSpace(b.String()), ". ")
	if name == "" {
		return "file"
	}
	stem := name
	if i := strings.IndexByte(name, '.'); i >= 0 {
		stem = name[:i]
	}
	if reserved[strings.ToUpper(strings.TrimSpace(stem))] {
		name = "_" + name
	}
	if len(name) > maxNameBytes {
		ext := filepath.Ext(name)
		if len(ext) > 20 {
			ext = ""
		}
		base := name[:maxNameBytes-len(ext)]
		for !utf8.ValidString(base) {
			base = base[:len(base)-1]
		}
		name = base + ext
	}
	return name
}

// SanitizeRelPath cleans a relative path from a folder upload, rejecting
// anything that could escape the save folder.
func SanitizeRelPath(rel string) (string, error) {
	if strings.HasPrefix(rel, "/") || strings.HasPrefix(rel, `\`) || filepath.VolumeName(rel) != "" || (len(rel) > 1 && rel[1] == ':') {
		return "", ErrBadPath
	}
	var parts []string
	for _, seg := range strings.FieldsFunc(rel, func(r rune) bool { return r == '/' || r == '\\' }) {
		switch seg {
		case "..":
			return "", ErrBadPath
		case ".":
			continue
		}
		parts = append(parts, SanitizeName(seg))
	}
	if len(parts) == 0 {
		return "", ErrBadPath
	}
	return filepath.Join(parts...), nil
}

// UniquePath picks a final path in dir for name that is not taken, adding
// " (1)", " (2)"… as needed. It atomically reserves the choice by creating
// "<path>.part"; the caller owns that file.
func UniquePath(dir, name string) (string, error) {
	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	for i := 0; i < 10000; i++ {
		cand := name
		if i > 0 {
			cand = fmt.Sprintf("%s (%d)%s", stem, i, ext)
		}
		p := filepath.Join(dir, cand)
		if _, err := os.Lstat(p); err == nil {
			continue
		}
		f, err := os.OpenFile(p+PartSuffix, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
		if errors.Is(err, os.ErrExist) {
			continue
		}
		if err != nil {
			return "", err
		}
		f.Close()
		return p, nil
	}
	return "", errors.New("no free file name")
}
