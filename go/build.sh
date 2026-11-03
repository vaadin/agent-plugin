#!/bin/sh
# Cross-compile the vaadin-agent-tools CLI for every supported OS/arch and place
# the binaries where the plugin launcher expects them (../plugin/cli/platform),
# or in the directory given as the first argument.
#
# No runtime is bundled or hunted for — each binary is fully self-contained
# (CGO disabled, symbols stripped). The plugin's cli/vaadin-agent-tools selector
# picks the right one by uname; it never looks outside the plugin directory.
#
# The build is reproducible: the same source gives byte-identical binaries on
# any host. The Go toolchain is pinned by the toolchain line in go.mod, and
# -buildvcs=false keeps the commit out of the binary, so CI can rebuild and
# compare them with what is committed.
set -e
cd "$(dirname "$0")" # go/

OUT="${1:-../plugin/cli/platform}"
mkdir -p "$OUT"

export CGO_ENABLED=0
GOTOOLCHAIN="$(sed -n 's/^toolchain //p' go.mod)"
export GOTOOLCHAIN
LDFLAGS="-s -w" # strip symbol/debug info → smaller, cleaner binaries (no UPX!)

build() { # os arch outfile
  echo "  $1/$2"
  GOOS="$1" GOARCH="$2" go build -trimpath -buildvcs=false -ldflags "$LDFLAGS" -o "$OUT/$3" .
}

echo "Building vaadin-agent-tools with $(go version | cut -d' ' -f3):"
build darwin  arm64 vaadin-agent-tools-darwin-arm64
build darwin  amd64 vaadin-agent-tools-darwin-amd64
build linux   amd64 vaadin-agent-tools-linux-amd64
build linux   arm64 vaadin-agent-tools-linux-arm64
build windows amd64 vaadin-agent-tools-windows-amd64.exe

echo "Done → $OUT"
ls -la "$OUT"
