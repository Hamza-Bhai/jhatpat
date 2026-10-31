// Package launch holds the small pieces a parent process (the Android app)
// needs to start and stop the JhatPat server.
package launch

import (
	"encoding/json"
	"io"
)

// StartupInfo is what the server prints, as one JSON line, once it is listening.
type StartupInfo struct {
	Port      int    `json:"port"`
	HostToken string `json:"hostToken"`
	PIN       string `json:"pin"`
	Secret    string `json:"secret"`
	IP        string `json:"ip"`
	HostURL   string `json:"hostUrl"`
}

// WriteStartup writes info to w as a single line of JSON ending in a newline.
func WriteStartup(w io.Writer, info StartupInfo) error {
	b, err := json.Marshal(info)
	if err != nil {
		return err
	}
	_, err = w.Write(append(b, '\n'))
	return err
}

// ExitWhenClosed reads and discards r until it ends (EOF or error), then
// calls cancel once. It blocks, so callers run it in a goroutine.
func ExitWhenClosed(r io.Reader, cancel func()) {
	_, _ = io.Copy(io.Discard, r)
	cancel()
}
