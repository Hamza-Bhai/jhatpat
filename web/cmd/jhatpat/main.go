// Command jhatpat runs the JhatPat LAN file transfer server.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"sync/atomic"
	"time"

	qrcode "github.com/skip2/go-qrcode"

	"github.com/Hamza-Bhai/jhatpat/web/internal/events"
	"github.com/Hamza-Bhai/jhatpat/web/internal/netdoctor"
	"github.com/Hamza-Bhai/jhatpat/web/internal/server"
	"github.com/Hamza-Bhai/jhatpat/web/web"
)

// version is set at release time: -ldflags "-X main.version=v1.2.3".
var version = "dev"

const (
	defaultPort = 8787
	maxPortTry  = 10
)

func main() {
	home, _ := os.UserHomeDir()
	cfgRoot, _ := os.UserConfigDir()
	port := flag.Int("port", defaultPort, "port to listen on (tries the next ones if busy)")
	dir := flag.String("dir", filepath.Join(home, "Downloads", "JhatPat"), "folder for received files")
	noBrowser := flag.Bool("no-browser", false, "do not open the host page automatically")
	preferIP := flag.String("ip", "", "network address to put in the QR code (default: best WiFi address)")
	showVersion := flag.Bool("version", false, "print the version and exit")
	flag.Parse()
	if *showVersion {
		fmt.Println("jhatpat", version)
		return
	}

	ln, err := listen(*port)
	if err != nil {
		fatal("JhatPat could not start: %v", err)
	}
	actualPort := ln.Addr().(*net.TCPAddr).Port

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()

	doctor := netdoctor.New(actualPort, *preferIP)
	srv, err := server.New(server.Config{
		Port:      actualPort,
		SaveDir:   *dir,
		ConfigDir: filepath.Join(cfgRoot, "JhatPat"),
		Web:       web.FS,
		OpenPath:  openPath,
		Doctor:    doctor,
		Quit:      stop, // "Quit" button on the host page (no terminal when double-clicked)
	})
	if err != nil {
		fatal("JhatPat could not start: %v", err)
	}

	var ip atomic.Value
	ip.Store("")
	if adapters, err := netdoctor.ListAdapters(); err == nil {
		for _, a := range adapters {
			if *preferIP == "" || a.IP == *preferIP {
				ip.Store(a.IP)
				break
			}
		}
	}
	srv.SetJoinURL(func() string {
		cur := ip.Load().(string)
		if cur == "" {
			return ""
		}
		return fmt.Sprintf("http://%s:%d/j/%s", cur, actualPort, srv.Join().Secret())
	})

	go watchNetwork(ctx, doctor, srv, &ip)

	httpSrv := &http.Server{Handler: srv.Handler(), ReadHeaderTimeout: 15 * time.Second}
	go func() {
		if err := httpSrv.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			fatal("JhatPat stopped: %v", err)
		}
	}()

	hostURL := fmt.Sprintf("http://localhost:%d/host?t=%s", actualPort, srv.HostToken())
	printBanner(srv, ip.Load().(string), actualPort, hostURL, *dir)
	if !*noBrowser {
		_ = openURL(hostURL)
	}

	<-ctx.Done()
	fmt.Println("\nJhatPat is shutting down…")
	shutCtx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = httpSrv.Shutdown(shutCtx)
	srv.Shutdown()
}

// fatal reports a startup error where the user can see it: a dialog on
// Windows (the GUI build has no console), stderr elsewhere, then exits.
func fatal(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	showError(msg)
	log.Fatal(msg)
}

// listen binds the first free port from start upwards.
func listen(start int) (net.Listener, error) {
	var lastErr error
	for p := start; p < start+maxPortTry; p++ {
		ln, err := net.Listen("tcp", fmt.Sprintf(":%d", p))
		if err == nil {
			return ln, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

// watchNetwork re-runs the doctor every 30s, tells the host page, and
// switches the QR address when the laptop's IP changes.
func watchNetwork(ctx context.Context, d *netdoctor.Doctor, srv *server.Server, ip *atomic.Value) {
	tick := time.NewTicker(30 * time.Second)
	defer tick.Stop()
	for {
		r := d.Run(ctx, netdoctor.Options{PhoneSeen: srv.PhoneSeen()})
		if r.IP != "" && r.IP != ip.Load().(string) {
			ip.Store(r.IP)
			srv.Hub().Send("host", events.Event{Type: "pin", Data: map[string]string{"ip": r.IP}})
		}
		srv.Hub().Send("host", events.Event{Type: "doctor", Data: r})
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}

func printBanner(srv *server.Server, ip string, port int, hostURL, dir string) {
	fmt.Println()
	fmt.Println("  ⚡ JhatPat is running")
	fmt.Println("  ──────────────────────────────────────────")
	fmt.Printf("  Laptop page : %s\n", hostURL)
	if ip == "" {
		fmt.Println("  Phone link  : (no WiFi address found — connect to WiFi)")
	} else {
		join := fmt.Sprintf("http://%s:%d", ip, port)
		fmt.Printf("  Phone link  : %s   PIN: %s\n", join, srv.Join().PIN())
		if q, err := qrcode.New(fmt.Sprintf("%s/j/%s", join, srv.Join().Secret()), qrcode.Low); err == nil {
			fmt.Println(q.ToSmallString(false))
		}
	}
	fmt.Printf("  Saving to   : %s\n", dir)
	fmt.Println("  Press Ctrl+C to stop.")
	fmt.Println()
}

func openURL(u string) error {
	switch runtime.GOOS {
	case "windows":
		return exec.Command("rundll32", "url.dll,FileProtocolHandler", u).Start()
	case "darwin":
		return exec.Command("open", u).Start()
	default:
		return exec.Command("xdg-open", u).Start()
	}
}

func openPath(p string) error {
	switch runtime.GOOS {
	case "windows":
		return exec.Command("explorer", p).Start()
	case "darwin":
		return exec.Command("open", p).Start()
	default:
		return exec.Command("xdg-open", p).Start()
	}
}
