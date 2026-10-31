//go:build windows

package main

import (
	"syscall"
	"unsafe"
)

// showError pops a message box; the Windows GUI build has no console to print to.
func showError(msg string) {
	user32 := syscall.NewLazyDLL("user32.dll")
	text, _ := syscall.UTF16PtrFromString(msg)
	title, _ := syscall.UTF16PtrFromString("JhatPat")
	const mbIconError = 0x10
	user32.NewProc("MessageBoxW").Call(0, uintptr(unsafe.Pointer(text)), uintptr(unsafe.Pointer(title)), mbIconError)
}
