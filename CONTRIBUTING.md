# Contributing

This repository is the source of the Vaadin plugin for Claude Code and Codex.
[plugin/README.md](plugin/README.md) is for people who install the plugin; this
file is for people who change it.

## Layout

Everything users install is under `plugin/`. Both hosts read their own manifest
and share the rest:

```text
plugin/
├── .claude-plugin/plugin.json   # Claude Code manifest
├── .codex-plugin/plugin.json    # Codex manifest
├── .mcp.json                    # the two hosted MCP servers, read by both hosts
├── README.md                    # user documentation and Claude directory listing text
├── LICENSE
├── assets/vaadin-icon.svg       # square icon for both directories
├── skills/                      # one directory per skill
├── hooks/hooks.json             # PostToolUse styling checks
└── cli/
    ├── vaadin-agent-tools       # POSIX launcher: picks the native binary by uname
    ├── vaadin-agent-tools.bat   # Windows launcher
    └── platform/                # native binaries, built by go/build.sh and committed
```

Outside `plugin/`:

- `go/`: source of the `vaadin-agent-tools` CLI.
- `test/fixtures/`: sample projects used by the Go tests.
- `tools/aura-reference/`: generator for the `aura-theme` skill's property tables.
- `scripts/`: packaging and maintenance scripts.
- `.claude-plugin/marketplace.json`, `.agents/plugins/marketplace.json`:
  one-plugin development marketplaces for each host.

Two layout rules come from the hosts:

- **No top-level `bin/` in `plugin/`.** claude.ai and Cowork refuse to install a
  plugin that has one, and directory installs from claude.ai sync into Claude
  Code. That's why the CLI lives in `cli/`, and skills and hooks call it by its
  full path.
- **Codex reads `.codex-plugin/plugin.json`.** OpenAI recommends a portable root
  `plugin.json` with an `extensions.com.openai` object instead, but Codex 0.163
  ignores the hooks declared there. Revisit when Codex supports it.

## Checks

```shell
python3 scripts/check_plugin.py                   # manifests, skills, directory limits
(cd go && go vet ./... && go test ./...)          # CLI
node --test 'tools/aura-reference/*.test.mjs'     # Aura reference generator
node tools/aura-reference/generate.mjs --check    # generated tables are current
claude plugin validate plugin                     # Claude Code's own validator
```

CI runs all but the last.

## Skills

Each skill is `plugin/skills/<name>/SKILL.md` plus optional `references/` that
the agent loads only when the skill tells it to.

- Keep the frontmatter `name` equal to the directory name, and prefix new
  skills with `vaadin-`.
- The `description` decides when an agent picks the skill up. Say what the skill
  does and list the requests that should trigger it. Keep it under 1,024
  characters (the OpenAI directory's limit).
- Refer to MCP tools by their bare names (`search_vaadin_docs`,
  `directory_search`). The fully qualified names differ between hosts.
- A skill that runs the CLI must work on both hosts. Claude Code replaces
  `${CLAUDE_PLUGIN_ROOT}` in skill text with the plugin's path; Codex does not,
  so give the path relative to the skill as well. Copy the "Run it" section of an
  existing tool skill.
- Update the skill table in `plugin/README.md`.

## The CLI (`vaadin-agent-tools`)

The checks and the project bootstrapper are a small Go CLI compiled ahead of
time for each platform, so the plugin needs no Node.js or JVM at runtime and
never looks outside its own directory for something to execute.

The command line is the contract: `vaadin-agent-tools <tool> [args] [--json]`,
a `{ "tool", "ok", ... }` JSON envelope, and exit codes `0` (no error-level
findings), `1` (findings or failure), `2` (usage error). The CLI has no version
of its own; it ships with, and is versioned as, the plugin.

### Rebuilding the binaries

The binaries in `plugin/cli/platform/` are committed so that a git install
works with no build step. When you change anything under `go/`, rebuild them on
macOS (the universal macOS binary needs `lipo`) and commit them in the same
change:

```shell
sh go/build.sh && git add plugin/cli/platform
```

Don't rebuild for changes outside `go/`: every rebuild adds about 28 MB to the
repository history.

### Adding a tool

1. Create `go/internal/tools/<name>.go` defining a `tool.Descriptor`.
2. Register it in the `registry` slice in `go/internal/cli/cli.go`.
3. Add `plugin/skills/vaadin-<name>/SKILL.md` so agents discover it.
4. Rebuild the binaries.

### The styling hook

`plugin/hooks/hooks.json` runs `vaadin-agent-tools hook post-tool-use` after
file edits. It stays silent unless an edited `.css` file, or a `.java` edit that
touches a Vaadin styling API, leaves an error-level theme-mixing or Aura-usage
finding. Test it by hand:

```shell
echo '{"tool_input":{"file_path":"/abs/path/App.java","new_string":"btn.getStyle().set(\"color\",\"red\");"}}' \
  | plugin/cli/vaadin-agent-tools hook post-tool-use
```

Codex edits through `apply_patch`, and the hook reads that payload too:

```shell
printf '%s' '{"cwd":"/abs/path/my-app","tool_name":"apply_patch","tool_input":{"command":"*** Begin Patch\n*** Update File: src/main/java/com/example/App.java\n@@\n+btn.getStyle().set(\"color\",\"red\");\n*** End Patch"}}' \
  | plugin/cli/vaadin-agent-tools hook post-tool-use
```

## Aura reference data

Two places embed facts about the Aura theme, both derived from the published
`@vaadin/aura` package:

- The generated blocks in `plugin/skills/aura-theme/references/property-values.md`,
  produced by `tools/aura-reference/`. Never edit them by hand; run
  `node tools/aura-reference/generate.mjs`. See
  [`tools/aura-reference/README.md`](tools/aura-reference/README.md).
- The property tables in `go/internal/tools/auradata.go`, used by
  `check-aura-usage`. Diff them against a release with
  `sh scripts/derive-aura-properties.sh <version>`.

When a new Aura version ships, update both.

## Testing the plugin locally

Uninstall any published copy of the plugin first, or both copies will load.

**Claude Code:**

```shell
claude plugin marketplace add ./
claude plugin install vaadin@vaadin-dev
```

Or load it for one session without installing: `claude --plugin-dir plugin`.

**Codex:**

```shell
codex plugin marketplace add .
codex plugin add vaadin@vaadin-dev
```

To try a change without touching your own setup, point `CLAUDE_CONFIG_DIR` or
`CODEX_HOME` at an empty directory first.

## Releasing

1. Bump `version` in `plugin/.claude-plugin/plugin.json` and
   `plugin/.codex-plugin/plugin.json`. `check_plugin.py` fails if they differ.
2. Add an entry to [CHANGELOG.md](CHANGELOG.md).
3. Merge to `main`, then publish to both directories.

### Claude plugin directory

Submit at [claude.ai/directory/manage](https://claude.ai/directory/manage)
(**Submit new**, then **Plugin bundle**), with this repository as the source,
`plugin` as the plugin path, and the branch or tag the listing should track.
Every new version is validated and security-scanned. The bundled binaries are
compiled executables, so expect a reviewer to look at them.

### OpenAI plugin directory

OpenAI takes an uploaded ZIP rather than a git source:

```shell
sh scripts/package-openai.sh    # writes dist/vaadin-openai-<version>.zip
```

The ZIP leaves out the hooks (OpenAI's directory rejects plugins with hooks) and
the Claude manifest, and marks the plugin as needing local execution. Upload it
at [platform.openai.com/plugins](https://platform.openai.com/plugins). Each
upload needs a new `version`.

The Vaadin marketplace ([`vaadin/agent-marketplace`](https://github.com/vaadin/agent-marketplace))
lists the full plugin, hooks included, for Codex users who install it manually.
