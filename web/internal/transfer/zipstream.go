package transfer

import (
	"archive/zip"
	"io"
	"io/fs"
	"os"
	"path/filepath"
)

// WriteZip streams root as an uncompressed zip to w. Entries are named
// "<base of root>/<relative path>"; symlinks are skipped.
func WriteZip(w io.Writer, root string) error {
	zw := zip.NewWriter(w)
	base := filepath.Base(root)
	err := filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.Type()&fs.ModeSymlink != 0 || d.IsDir() || !d.Type().IsRegular() {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(root, p)
		if err != nil {
			return err
		}
		hdr, err := zip.FileInfoHeader(info)
		if err != nil {
			return err
		}
		hdr.Name = filepath.ToSlash(filepath.Join(base, rel))
		hdr.Method = zip.Store
		dst, err := zw.CreateHeader(hdr)
		if err != nil {
			return err
		}
		f, err := os.Open(p)
		if err != nil {
			return err
		}
		defer f.Close()
		_, err = io.Copy(dst, f)
		return err
	})
	if err != nil {
		return err
	}
	return zw.Close()
}
