package netdoctor

import (
	"context"
	"errors"
	"net"
	"testing"
)

func ifc(name string, ip string) IfaceInfo {
	return IfaceInfo{Name: name, Flags: net.FlagUp, Addrs: []net.IP{net.ParseIP(ip)}}
}

func TestRankPrefersWiFiOverVirtual(t *testing.T) {
	got := RankAdapters([]IfaceInfo{
		ifc("vEthernet (WSL)", "172.20.0.1"),
		ifc("Wi-Fi", "192.168.1.5"),
		ifc("Ethernet 2", "10.0.0.7"),
	})
	if len(got) != 3 || got[0].Name != "Wi-Fi" || !got[2].Virtual {
		t.Fatalf("got %+v", got)
	}
}

func TestRankExcludesLoopbackLinkLocalAndDown(t *testing.T) {
	down := ifc("Wi-Fi 2", "192.168.9.9")
	down.Flags = 0
	got := RankAdapters([]IfaceInfo{
		{Name: "Loopback", Flags: net.FlagUp | net.FlagLoopback, Addrs: []net.IP{net.ParseIP("127.0.0.1")}},
		ifc("Ethernet", "169.254.3.4"),
		ifc("Wi-Fi", "fe80::1"),
		down,
	})
	if len(got) != 0 {
		t.Fatalf("got %+v", got)
	}
}

type fakePlatform struct {
	profile    string
	profileErr error
	allowed    bool
	fwErr      error
}

func (f fakePlatform) NetworkProfile(string) (string, error) { return f.profile, f.profileErr }
func (f fakePlatform) FirewallAllows(string, int) (bool, error) {
	return f.allowed, f.fwErr
}
func (f fakePlatform) AddFirewallRule(string, int) error { return nil }

func doctorWith(p Platform, ips ...string) *Doctor {
	n := 0
	return &Doctor{
		Port:     8787,
		Platform: p,
		Adapters: func() ([]Adapter, error) {
			ip := ips[min(n, len(ips)-1)]
			n++
			return RankAdapters([]IfaceInfo{ifc("Wi-Fi", ip)}), nil
		},
		Reach: func(context.Context, string, int) error { return nil },
	}
}

func check(r Report, id string) Check {
	for _, c := range r.Checks {
		if c.ID == id {
			return c
		}
	}
	return Check{}
}

func TestAllGood(t *testing.T) {
	r := doctorWith(fakePlatform{profile: "Private", allowed: true}, "192.168.1.5").Run(context.Background(), Options{})
	if !r.OK || r.IP != "192.168.1.5" {
		t.Fatalf("got %+v", r)
	}
}

func TestPublicProfileFails(t *testing.T) {
	r := doctorWith(fakePlatform{profile: "Public", allowed: false}, "192.168.1.5").Run(context.Background(), Options{})
	c := check(r, "profile")
	if r.OK || c.Status != Fail || c.Detail != "Public" {
		t.Fatalf("got %+v", r)
	}
}

func TestFirewallBlockedFails(t *testing.T) {
	r := doctorWith(fakePlatform{profile: "Private"}, "192.168.1.5").Run(context.Background(), Options{})
	if c := check(r, "firewall"); c.Status != Fail || r.OK {
		t.Fatalf("got %+v", r)
	}
}

func TestPlatformErrorIsWarn(t *testing.T) {
	r := doctorWith(fakePlatform{profileErr: errors.New("no powershell"), fwErr: errors.New("x")}, "192.168.1.5").Run(context.Background(), Options{})
	if check(r, "profile").Status != Warn || check(r, "firewall").Status != Warn || !r.OK {
		t.Fatalf("got %+v", r)
	}
}

func TestNotApplicableProfileOmitted(t *testing.T) {
	r := doctorWith(fakePlatform{profile: "", allowed: true}, "192.168.1.5").Run(context.Background(), Options{})
	if check(r, "profile").ID != "" {
		t.Fatalf("got %+v", r)
	}
}

func TestNoAdapterFails(t *testing.T) {
	d := doctorWith(fakePlatform{allowed: true}, "192.168.1.5")
	d.Adapters = func() ([]Adapter, error) { return nil, nil }
	r := d.Run(context.Background(), Options{})
	if r.OK || check(r, "adapter").Status != Fail {
		t.Fatalf("got %+v", r)
	}
}

func TestUnreachableFails(t *testing.T) {
	d := doctorWith(fakePlatform{profile: "Private", allowed: true}, "192.168.1.5")
	d.Reach = func(context.Context, string, int) error { return errors.New("refused") }
	if r := d.Run(context.Background(), Options{}); r.OK || check(r, "reachable").Status != Fail {
		t.Fatalf("got %+v", r)
	}
}

func TestPhoneTroubleAddsIsolationHelp(t *testing.T) {
	r := doctorWith(fakePlatform{profile: "Private", allowed: true}, "192.168.1.5").Run(context.Background(), Options{PhoneTrouble: true})
	if check(r, "no-phone").Status != Warn {
		t.Fatalf("got %+v", r)
	}
}

func TestIPChangeDetected(t *testing.T) {
	d := doctorWith(fakePlatform{profile: "Private", allowed: true}, "192.168.1.5", "192.168.0.42")
	first := d.Run(context.Background(), Options{})
	second := d.Run(context.Background(), Options{})
	if first.IP != "192.168.1.5" || second.IP != "192.168.0.42" {
		t.Fatalf("got %s then %s", first.IP, second.IP)
	}
}

func TestPreferredIPWins(t *testing.T) {
	d := doctorWith(fakePlatform{profile: "Private", allowed: true}, "192.168.1.5")
	d.Adapters = func() ([]Adapter, error) {
		return RankAdapters([]IfaceInfo{ifc("Wi-Fi", "192.168.1.5"), ifc("Ethernet", "10.0.0.9")}), nil
	}
	d.PreferIP = "10.0.0.9"
	if r := d.Run(context.Background(), Options{}); r.IP != "10.0.0.9" {
		t.Fatalf("got %s", r.IP)
	}
}

func TestPublicWithFirewallAllowedIsWarn(t *testing.T) {
	r := doctorWith(fakePlatform{profile: "Public", allowed: true}, "192.168.1.5").Run(context.Background(), Options{})
	c := check(r, "profile")
	if c.Status != Warn || c.Detail != "Public" || !r.OK {
		t.Fatalf("got %+v", r)
	}
}

func TestPublicWithFirewallUnknownFails(t *testing.T) {
	r := doctorWith(fakePlatform{profile: "Public", fwErr: errors.New("x")}, "192.168.1.5").Run(context.Background(), Options{})
	if check(r, "profile").Status != Fail || r.OK {
		t.Fatalf("got %+v", r)
	}
}
