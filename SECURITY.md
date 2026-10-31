# Security Policy

## Supported versions

Only the latest release receives security fixes.

## Reporting a vulnerability

Please **do not open a public issue** for security problems.

Use GitHub's private reporting instead: open the **Security** tab of this repository and choose **Report a vulnerability**.

Include what you found, how to reproduce it, and the version (`jhatpat -version`). You can expect a first reply within a few days.

## Scope and design notes

JhatPat is meant for trusted local networks:

- It serves plain HTTP on your LAN and never contacts the internet.
- Joining requires the QR secret or PIN **and** approval by the person at the laptop; wrong PINs are rate-limited.
- The laptop page only accepts requests from the laptop itself and needs a per-run token.
- Anyone on the same network who can capture traffic can read files in transit. Do not use JhatPat on networks you do not trust.
