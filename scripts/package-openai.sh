#!/bin/sh
# Build the ZIP to upload to OpenAI's plugin directory (platform.openai.com/plugins).
#
# The Claude directory installs plugin/ straight from git; OpenAI takes an
# uploaded ZIP and rejects some of what plugin/ contains, so the ZIP differs:
#
#   - hooks/ is left out. Plugins with lifecycle hooks can't be submitted to
#     OpenAI's directory, so the post-edit styling checks are only available to
#     Codex users who install from this repository's marketplace.
#   - .claude-plugin/ is left out, so .codex-plugin/plugin.json is the only
#     manifest OpenAI reads.
#   - "requires_local_executor": true is added to the Codex manifest, because
#     three skills run the bundled CLI. This marks the listing "Desktop only".
#
# Usage: sh scripts/package-openai.sh    →  dist/vaadin-openai-<version>.zip
set -e
cd "$(dirname "$0")/.."

VERSION="$(python3 -c 'import json; print(json.load(open("plugin/.codex-plugin/plugin.json"))["version"])')"
OUT="dist/vaadin-openai-$VERSION.zip"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE/vaadin"
cp -R plugin/. "$STAGE/vaadin/"
rm -rf "$STAGE/vaadin/hooks" "$STAGE/vaadin/.claude-plugin"
find "$STAGE" -name .DS_Store -delete

python3 - "$STAGE/vaadin/.codex-plugin/plugin.json" <<'EOF'
import json, sys
path = sys.argv[1]
manifest = json.load(open(path))
manifest["requires_local_executor"] = True
with open(path, "w") as f:
    json.dump(manifest, f, indent=2)
    f.write("\n")
EOF

mkdir -p dist
rm -f "$OUT"
(cd "$STAGE" && zip -qrX - vaadin) > "$OUT"
echo "Wrote $OUT ($(du -h "$OUT" | cut -f1 | tr -d ' '))"
