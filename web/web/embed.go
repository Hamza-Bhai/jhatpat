// Package web holds JhatPat's pages and static assets, embedded in the binary.
package web

import "embed"

// FS contains host.html, client.html, join.html and static/.
//
//go:embed *.html static
var FS embed.FS
