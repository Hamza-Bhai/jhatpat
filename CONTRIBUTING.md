# Contributing to JhatPat

Thanks for helping! Bug reports, ideas, documentation fixes and pull requests are all welcome.

## Before you start

- Search existing [issues](../../issues) first.
- For anything bigger than a small fix, open an issue to discuss it before writing code.
- Be kind: see the [Code of Conduct](CODE_OF_CONDUCT.md).

## Development setup

You need [Go](https://go.dev/dl/) 1.24 or newer. No other tools are required: the web UI is plain HTML, CSS and JavaScript embedded into the binary.

```bash
git clone https://github.com/Hamza-Bhai/jhatpat
cd jhatpat
go test ./...
go run ./cmd/jhatpat
```

On Windows, `build-windows.bat` builds `dist\jhatpat.exe` without a terminal window.

## Pull requests

1. Create a branch from `main`.
2. Keep the change focused. One topic per pull request.
3. Add or update tests for behavior changes.
4. Run `gofmt -l .`, `go vet ./...` and `go test ./...`. CI runs them on Windows, Linux and macOS.
5. Update `CHANGELOG.md` under **Unreleased** for user-visible changes.
6. Use clear commit messages. [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`) are appreciated.

## Project conventions

- **No dependencies without a reason.** The binary is small and standard-library based; discuss new dependencies first.
- **Privacy first.** A device must never see items that were not sent to it. Anything touching routing or authorization needs a test.
- **English only** for code, comments, UI text, docs and commit messages.
- **Plain language for users.** Error messages should say what is wrong and how to fix it.
- **Manual testing.** UI and network changes should be checked on a real phone; see [docs/manual-testing.md](docs/manual-testing.md).

## Reporting security issues

Please follow [SECURITY.md](SECURITY.md) instead of opening a public issue.
