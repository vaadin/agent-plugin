# Changelog

## 0.1.0 (unreleased)

First version of the combined Vaadin plugin. It replaces `vaadin-skills`
0.3.0 ([`vaadin/agent-skills`](https://github.com/vaadin/agent-skills) at
`6e74fcd`) and `vaadin-agent-tools` 0.3.2
([`vaadin/agent-tools`](https://github.com/vaadin/agent-tools) at `322ce3c`).

### Added

- The Vaadin Directory MCP server (`https://vaadin.com/directory/mcp`), next to
  the Vaadin documentation MCP server
  ([agent-skills#13](https://github.com/vaadin/agent-skills/issues/13)).
- `vaadin-add-ons` skill for finding, evaluating, and installing Vaadin
  Directory add-ons.
- `vaadin-usage-review` now also runs the styling checkers when the review
  covers CSS or Java that sets styles.
- Manifest metadata for the Claude and OpenAI plugin directories, and a
  square icon.

### Changed

- The bundled CLI moved from `bin/` to `cli/`. claude.ai and Cowork refuse
  plugins with a top-level `bin/` directory, and directory installs from
  claude.ai sync into Claude Code. Skills run it through its full path.
- The CLI no longer has a version of its own, so a release that changes no Go
  code doesn't need new binaries
  ([agent-tools#2](https://github.com/vaadin/agent-tools/issues/2)).
- Skills name MCP tools by their real names instead of `mcp_vaadin_…`.
- `vaadin-create-project` downloads the skeleton with `curl`, and the CLI's
  `create-project` only extracts it (`--skeleton=<file.zip>`; `--name`,
  `--example`, and `--pre` became URL parameters in the skill). The CLI no
  longer accesses the network, which halves the size of each binary.
- Requests to start.vaadin.com identify the source as `ref=agent-plugin`
  instead of `ref=agent-tools`.
- macOS gets separate `arm64` and `amd64` binaries instead of a universal one,
  to stay under the Claude plugin directory's 5 MiB limit per file.
- The binaries are built reproducibly with Go 1.27.3, and CI checks that they
  match the source.

### Fixed

- The CLI launcher found no binary under Git Bash on Windows, which Claude Code
  uses for Bash commands and hooks.
