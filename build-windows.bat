@echo off
rem Builds dist\jhatpat.exe as a GUI-subsystem app: double-clicking it opens only
rem the browser page, no terminal window. Quit from the button on the host page.
set CGO_ENABLED=0
go build -ldflags "-H=windowsgui -s -w -X main.version=dev" -o dist\jhatpat.exe .\cmd\jhatpat
