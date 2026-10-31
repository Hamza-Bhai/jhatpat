package launch

import (
	"bytes"
	"encoding/json"
	"io"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestWriteStartupIsOneJSONLine(t *testing.T) {
	want := StartupInfo{Port: 8787, HostToken: "tok", PIN: "1234", Secret: "sec", IP: "192.168.43.1", HostURL: "http://127.0.0.1:8787/host?t=tok"}
	var buf bytes.Buffer
	if err := WriteStartup(&buf, want); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	if strings.Count(out, "\n") != 1 || !strings.HasSuffix(out, "\n") {
		t.Fatalf("want exactly one trailing newline, got %q", out)
	}
	var got StartupInfo
	if err := json.Unmarshal([]byte(out), &got); err != nil {
		t.Fatal(err)
	}
	if got != want {
		t.Fatalf("round trip: got %+v want %+v", got, want)
	}
}

func TestStartupJSONFieldNames(t *testing.T) {
	var buf bytes.Buffer
	_ = WriteStartup(&buf, StartupInfo{})
	for _, k := range []string{`"port"`, `"hostToken"`, `"pin"`, `"secret"`, `"ip"`, `"hostUrl"`} {
		if !strings.Contains(buf.String(), k) {
			t.Errorf("missing field %s in %s", k, buf.String())
		}
	}
}

func TestExitWhenClosedCallsCancelOnEOF(t *testing.T) {
	var calls atomic.Int32
	ExitWhenClosed(strings.NewReader(""), func() { calls.Add(1) })
	if calls.Load() != 1 {
		t.Fatalf("cancel called %d times, want 1", calls.Load())
	}
}

func TestExitWhenClosedIgnoresData(t *testing.T) {
	pr, pw := io.Pipe()
	var calls atomic.Int32
	done := make(chan struct{})
	go func() {
		ExitWhenClosed(pr, func() { calls.Add(1) })
		close(done)
	}()
	_, _ = pw.Write([]byte("some bytes the app might send"))
	time.Sleep(50 * time.Millisecond)
	if calls.Load() != 0 {
		t.Fatalf("cancel called before EOF")
	}
	_ = pw.Close()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("ExitWhenClosed did not return after EOF")
	}
	if calls.Load() != 1 {
		t.Fatalf("cancel called %d times after EOF, want 1", calls.Load())
	}
}
