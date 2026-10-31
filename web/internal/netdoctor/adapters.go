// Package netdoctor finds the right network address and explains what is
// blocking phones from reaching the laptop.
package netdoctor

import (
	"net"
	"sort"
	"strings"
)

// IfaceInfo is the subset of a network interface ranking needs.
type IfaceInfo struct {
	Name  string
	Flags net.Flags
	Addrs []net.IP
}

// Adapter is one usable IPv4 address with a preference score.
type Adapter struct {
	Name    string `json:"name"`
	IP      string `json:"ip"`
	Virtual bool   `json:"virtual"`
	Score   int    `json:"score"`
}

var virtualHints = []string{"vethernet", "virtualbox", "vmware", "hyper-v", "wsl", "docker", "tun", "tap", "wg", "tailscale", "zerotier", "utun", "vpn", "bridge", "vbox"}

func hasAny(s string, words ...string) bool {
	for _, w := range words {
		if strings.Contains(s, w) {
			return true
		}
	}
	return false
}

// RankAdapters keeps up, non-loopback, non-link-local IPv4 addresses and
// orders them best first: real WiFi, then Ethernet, then the rest; virtual
// and VPN adapters last.
func RankAdapters(ifs []IfaceInfo) []Adapter {
	var out []Adapter
	for _, in := range ifs {
		if in.Flags&net.FlagUp == 0 || in.Flags&net.FlagLoopback != 0 {
			continue
		}
		name := strings.ToLower(in.Name)
		virtual := hasAny(name, virtualHints...)
		for _, ip := range in.Addrs {
			v4 := ip.To4()
			if v4 == nil || v4.IsLoopback() || v4.IsLinkLocalUnicast() {
				continue
			}
			score := 0
			switch {
			case hasAny(name, "wi-fi", "wifi", "wlan", "wireless", "wl"):
				score += 30
			case hasAny(name, "ethernet", "eth", "en"):
				score += 20
			}
			if v4.IsPrivate() {
				score += 10
			}
			if virtual {
				score -= 50
			}
			out = append(out, Adapter{Name: in.Name, IP: v4.String(), Virtual: virtual, Score: score})
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Score > out[j].Score })
	return out
}

// ListAdapters ranks the machine's real interfaces.
func ListAdapters() ([]Adapter, error) {
	ifs, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	infos := make([]IfaceInfo, 0, len(ifs))
	for _, in := range ifs {
		addrs, err := in.Addrs()
		if err != nil {
			continue
		}
		info := IfaceInfo{Name: in.Name, Flags: in.Flags}
		for _, a := range addrs {
			if n, ok := a.(*net.IPNet); ok {
				info.Addrs = append(info.Addrs, n.IP)
			}
		}
		infos = append(infos, info)
	}
	return RankAdapters(infos), nil
}
