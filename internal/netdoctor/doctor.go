package netdoctor

import (
	"context"
	"fmt"
	"net"
	"os"
	"time"
)

// Check statuses. Texts for each check ID live in the web UI's i18n file.
const (
	OK   = "ok"
	Warn = "warn"
	Fail = "fail"
)

// Check is one finding. ID is one of: adapter, profile, firewall, reachable, no-phone.
type Check struct {
	ID     string `json:"id"`
	Status string `json:"status"`
	Detail string `json:"detail,omitempty"`
}

// Report is the result of one Doctor run.
type Report struct {
	IP       string    `json:"ip"`
	Port     int       `json:"port"`
	Adapter  string    `json:"adapter"`
	Adapters []Adapter `json:"adapters"`
	Checks   []Check   `json:"checks"`
	OK       bool      `json:"ok"`
}

// Platform answers OS-specific questions. Methods may return errors; the
// Doctor turns them into warnings instead of failing.
type Platform interface {
	// NetworkProfile returns "Public", "Private", "DomainAuthenticated", or "" when not applicable.
	NetworkProfile(adapter string) (string, error)
	FirewallAllows(exePath string, port int) (bool, error)
	AddFirewallRule(exePath string, port int) error
}

// Options tune a single run.
type Options struct {
	// PhoneTrouble is set when the user says their phone cannot open the link.
	PhoneTrouble bool
}

// Doctor runs network checks. Adapters and Reach are injectable for tests.
type Doctor struct {
	Port     int
	PreferIP string
	Platform Platform
	Adapters func() ([]Adapter, error)
	Reach    func(ctx context.Context, ip string, port int) error
}

// New returns a Doctor wired to the real system.
func New(port int, preferIP string) *Doctor {
	return &Doctor{Port: port, PreferIP: preferIP, Platform: systemPlatform{}, Adapters: ListAdapters, Reach: dialReach}
}

func dialReach(ctx context.Context, ip string, port int) error {
	d := net.Dialer{Timeout: 2 * time.Second}
	c, err := d.DialContext(ctx, "tcp", net.JoinHostPort(ip, fmt.Sprint(port)))
	if err != nil {
		return err
	}
	return c.Close()
}

// Run performs all checks.
func (d *Doctor) Run(ctx context.Context, opt Options) Report {
	r := Report{Port: d.Port}
	add := func(id, status, detail string) { r.Checks = append(r.Checks, Check{id, status, detail}) }

	adapters, err := d.Adapters()
	r.Adapters = adapters
	var chosen *Adapter
	for i := range adapters {
		if d.PreferIP != "" && adapters[i].IP == d.PreferIP {
			chosen = &adapters[i]
		}
	}
	if chosen == nil && len(adapters) > 0 {
		chosen = &adapters[0]
	}
	switch {
	case err != nil || chosen == nil:
		add("adapter", Fail, "")
	case chosen.Virtual:
		add("adapter", Warn, chosen.Name)
	default:
		add("adapter", OK, chosen.Name)
	}
	if chosen != nil {
		r.IP, r.Adapter = chosen.IP, chosen.Name

		exe, _ := os.Executable()
		fwAllowed, fwErr := d.Platform.FirewallAllows(exe, d.Port)

		// A Public profile only blocks phones when the firewall has no rule
		// letting JhatPat in; with one, it is just worth knowing.
		if prof, err := d.Platform.NetworkProfile(chosen.Name); err != nil {
			add("profile", Warn, "")
		} else if prof == "Public" && fwErr == nil && fwAllowed {
			add("profile", Warn, prof)
		} else if prof == "Public" {
			add("profile", Fail, prof)
		} else if prof != "" {
			add("profile", OK, prof)
		}

		switch {
		case fwErr != nil:
			add("firewall", Warn, "")
		case !fwAllowed:
			add("firewall", Fail, "")
		default:
			add("firewall", OK, "")
		}

		if err := d.Reach(ctx, chosen.IP, d.Port); err != nil {
			add("reachable", Fail, err.Error())
		} else {
			add("reachable", OK, "")
		}
	}
	if opt.PhoneTrouble {
		add("no-phone", Warn, "")
	}

	r.OK = true
	for _, c := range r.Checks {
		if c.Status == Fail {
			r.OK = false
		}
	}
	return r
}

// FixFirewall asks the OS (with an admin prompt) to allow JhatPat through.
func (d *Doctor) FixFirewall() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	return d.Platform.AddFirewallRule(exe, d.Port)
}
