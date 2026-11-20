# Contributing

This repository is the source of the Vaadin plugin for Claude Code and Codex.
[plugin/README.md](plugin/README.md) is for people who install the plugin; this
file is for people who change it.

## Layout

Everything users install is under `plugin/`. Both hosts read their own manifest
and share the rest:

```text
plugin/
├── .claude-plugin/plugin.json   # Claude Code manifest, and its override of the docs server
├── .codex-plugin/plugin.json    # Codex manifest
├── .mcp.json                    # the two hosted MCP servers, read by both hosts
├── README.md                    # user documentation and Claude directory listing text
├── LICENSE
├── assets/vaadin-icon.svg       # square icon for both directories
├── skills/                      # one directory per skill
├── hooks/hooks.json             # PostToolUse styling checks
└── cli/
    ├── VaadinAgentTools.java    # the whole vaadin-agent-tools CLI, as source
    ├── vaadin-agent-tools       # POSIX launcher: runs it with java
    └── vaadin-agent-tools.bat   # Windows launcher, for shells without sh
```

Outside `plugin/`:

- `test/VaadinAgentToolsTest.java`: the CLI's tests.
- `test/fixtures/`: sample projects used by the CLI's tests.
- `tools/aura-reference/`: generator for the `aura-theme` skill's property tables.
- `scripts/`: packaging and maintenance scripts.
- `.claude-plugin/marketplace.json`, `.agents/plugins/marketplace.json`:
  one-plugin development marketplaces for each host.

Three layout rules come from the hosts:

- **No top-level `bin/` in `plugin/`.** claude.ai and Cowork refuse to install a
  plugin that has one, and directory installs from claude.ai sync into Claude
  Code. That's why the CLI lives in `cli/`, and skills and hooks call it by its
  full path.
- **Codex reads `.codex-plugin/plugin.json`.** OpenAI recommends a portable root
  `plugin.json` with an `extensions.com.openai` object instead, but Codex 0.163
  ignores the hooks declared there. Revisit when Codex supports it.
- **No variables in `.mcp.json`.** Codex doesn't expand them and would use the
  URL literally. The `vaadin` server is declared a second time, in
  `.claude-plugin/plugin.json`, with the URL
  `${VAADIN_DOCS_MCP_URL:-https://mcp.vaadin.com/docs}`. Claude Code loads both
  files and uses the manifest's server in place of the one of the same name in
  `.mcp.json`; that's what Claude Code 2.1.318 does, but it isn't documented.
  Change both together: `check_plugin.py` fails unless they match once the
  default is substituted.

## Checks

```shell
python3 scripts/check_plugin.py                   # manifests, skills, directory limits
sh scripts/test-cli.sh                            # CLI (needs a JDK 17 or later)
scripts\test-launcher.bat                         # the .bat launcher (Windows only)
node --test 'tools/aura-reference/*.test.mjs'     # Aura reference generator
node tools/aura-reference/generate.mjs --check    # generated tables are current
claude plugin validate plugin                     # Claude Code's own validator
```

CI runs all but the last. It runs the CLI tests with Java 17 on Linux, macOS,
and Windows, and with Java 25 on Linux, and checks that the launchers turn
down Java 11.

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

The checks and the project bootstrapper are one Java source file,
`plugin/cli/VaadinAgentTools.java`. The launcher runs it with
`java VaadinAgentTools.java`, Java's source-file mode (JEP 330), which compiles
it in memory on every run. The plugin ships no compiled code, because the Claude
plugin directory's review holds back plugins with executables or bytecode in
them; `check_plugin.py` fails on any. A Vaadin developer is the one user sure to
have a JDK, so this needs no runtime they don't already have.

Rules that keep this working:

- **One file.** Source-file mode runs a program from several files only from
  Java 22 (JEP 458), and the CLI must run on Java 17, the oldest JDK Vaadin 24
  supports. Use nested classes.
- **Java 17 and the JDK only.** There is no build to fetch a dependency. The
  JDK has no JSON parser, so the file has a small one. CI compiles the file
  with JDK 17, with `-Xlint:all -Werror`, since every run would print a
  compiler warning.
- **ASCII only.** JDK 17 reads source in the platform's charset, and a
  non-ASCII character fails the compile under a C or POSIX locale. Write
  Unicode escapes in strings. A test checks this.
- **No network access.** `vaadin-create-project` downloads the skeleton with
  `curl` and hands the ZIP to `create-project`.

The launcher prefers the JDK in `JAVA_HOME`, like Maven and Gradle, then the
one the `java` on the `PATH` belongs to. It needs Java 17 or later with the
compiler (the `jdk.compiler` module), which a JRE or a jlink-built runtime may
lack. It reads both from the JDK's `release` file, so checking costs no extra
start of Java, and asks `java` when there is no such file. Without one, it
explains on stderr and exits with `3`, and the skills tell the agent to pass
that on; the hook stays silent. Claude Code runs hooks and
Bash commands through Git Bash on Windows, so the `sh` launcher covers Windows
too; the `.bat` one is for cmd and PowerShell.

The command line is the contract: `vaadin-agent-tools <tool> [args] [--json]`,
a `{ "tool", "ok", ... }` JSON envelope, and exit codes `0` (no error-level
findings), `1` (findings or failure), `2` (usage error). The CLI has no version
of its own; it ships with, and is versioned as, the plugin.

To run the CLI from a checkout, call the launcher:

```shell
plugin/cli/vaadin-agent-tools check-aura-usage test/fixtures/aura-readonly
```

### Tests

`test/VaadinAgentToolsTest.java` is plain Java: every static `test*` method is
a test, and they reach into the CLI's package-private classes, so the two files
are compiled together. `sh scripts/test-cli.sh` compiles and runs them, then
checks the launcher: exit codes, what happens without a suitable JDK, and the
hook's pre-filter. Pass part of a test name to run only the matching tests. Set
`OLD_JDK` to a JDK older than 17 to check that it's turned down, too.
`scripts\test-launcher.bat` checks the `.bat` launcher on Windows.

### Adding a tool

1. Add a nested class to `VaadinAgentTools.java` with a `run(Args)` method
   that returns a `Result`, next to the existing tools.
2. List it in `TOOLS`.
3. Add tests to `test/VaadinAgentToolsTest.java`.
4. Add `plugin/skills/vaadin-<name>/SKILL.md` so agents discover it.

### The styling hook

`plugin/hooks/hooks.json` runs `vaadin-agent-tools hook post-tool-use` after
file edits. It stays silent unless an edited `.css` file, or a `.java` edit that
touches a Vaadin styling API, leaves an error-level theme-mixing or Aura-usage
finding.

Compiling the CLI takes a few tenths of a second, so the launcher skips Java for
most edits: it greps the event for words that every styling edit contains, and
exits when there are none. Its word list must cover every alternative of
`Hook.JAVA_STYLING`, which a test checks; update both together.

Test it by hand:

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
- The property tables in `AuraData` in `plugin/cli/VaadinAgentTools.java`, used
  by `check-aura-usage`. Diff them against a release with
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
Every new version is validated and security-scanned. A plugin with compiled
code in it, executables or bytecode, is held for human review, which is why the
CLI ships as source.

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
