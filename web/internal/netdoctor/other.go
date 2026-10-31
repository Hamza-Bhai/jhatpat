//go:build !windows

package netdoctor

import "errors"

// systemPlatform on macOS/Linux: network profiles do not apply, and the
// firewall cannot be inspected without root, so it is reported as allowed
// (macOS shows its own "Allow incoming connections" prompt).
type systemPlatform struct{}

func (systemPlatform) NetworkProfile(string) (string, error)    { return "", nil }
func (systemPlatform) FirewallAllows(string, int) (bool, error) { return true, nil }
func (systemPlatform) AddFirewallRule(string, int) error {
	return errors.New("not supported on this system")
}
