//go:build windows

package netdoctor

import (
	"context"
	"fmt"
	"os/exec"
	"strconv"
	"strings"
	"syscall"
	"time"
)

type systemPlatform struct{}

const ruleName = "JhatPat"

func psQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }

func powershell(timeout time.Duration, script string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, "powershell", "-NoProfile", "-NonInteractive", "-Command", script)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	out, err := cmd.Output()
	return strings.TrimSpace(string(out)), err
}

func (systemPlatform) NetworkProfile(adapter string) (string, error) {
	out, err := powershell(10*time.Second, fmt.Sprintf(
		"(Get-NetConnectionProfile -InterfaceAlias %s -ErrorAction Stop).NetworkCategory", psQuote(adapter)))
	if err != nil {
		return "", err
	}
	return strings.Split(out, "\n")[0], nil
}

// FirewallAllows is true when an enabled inbound allow rule exists for our
// port rule or our exe, and no enabled inbound block rule targets the exe
// (Windows creates block rules when the user dismisses its prompt).
func (systemPlatform) FirewallAllows(exePath string, port int) (bool, error) {
	script := fmt.Sprintf(`
$in = { $_.Enabled -eq 'True' -and $_.Direction -eq 'Inbound' }
$byName = @(Get-NetFirewallRule -DisplayName %s -ErrorAction SilentlyContinue | Where-Object $in | Where-Object { $_.Action -eq 'Allow' }).Count
$app = @(Get-NetFirewallApplicationFilter -Program %s -ErrorAction SilentlyContinue | Get-NetFirewallRule | Where-Object $in)
$allow = @($app | Where-Object { $_.Action -eq 'Allow' }).Count
$block = @($app | Where-Object { $_.Action -eq 'Block' }).Count
"$($byName + $allow) $block"`, psQuote(ruleName), psQuote(exePath))
	out, err := powershell(20*time.Second, script)
	if err != nil {
		return false, err
	}
	f := strings.Fields(out)
	if len(f) != 2 {
		return false, fmt.Errorf("unexpected output %q", out)
	}
	allow, err1 := strconv.Atoi(f[0])
	block, err2 := strconv.Atoi(f[1])
	if err1 != nil || err2 != nil {
		return false, fmt.Errorf("unexpected output %q", out)
	}
	return allow > 0 && block == 0, nil
}

// AddFirewallRule shows the Windows admin (UAC) prompt and adds an inbound
// allow rule for the port, removing block rules Windows made for the exe.
func (systemPlatform) AddFirewallRule(exePath string, port int) error {
	inner := fmt.Sprintf(
		"Get-NetFirewallApplicationFilter -Program %s -ErrorAction SilentlyContinue | Get-NetFirewallRule | Where-Object { $_.Action -eq 'Block' } | Remove-NetFirewallRule; "+
			"Remove-NetFirewallRule -DisplayName %s -ErrorAction SilentlyContinue; "+
			"New-NetFirewallRule -DisplayName %s -Direction Inbound -Action Allow -Protocol TCP -LocalPort %d -Profile Any | Out-Null",
		psQuote(exePath), psQuote(ruleName), psQuote(ruleName), port)
	script := fmt.Sprintf("Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile','-Command',%s",
		psQuote(inner))
	_, err := powershell(2*time.Minute, script)
	return err
}
