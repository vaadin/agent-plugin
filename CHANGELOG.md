# Changelog

## 0.1.0 (unreleased)

First version of the combined Vaadin plugin. It replaces `vaadin-skills`
0.2.1 ([`vaadin/agent-skills`](https://github.com/vaadin/agent-skills) at
`21caf0a`) and `vaadin-agent-tools` 0.3.2
([`vaadin/agent-tools`](https://github.com/vaadin/agent-tools) at `322ce3c`).

### Added

- The Vaadin Directory MCP server (`https://vaadin.com/directory/mcp`), next to
  the Vaadin documentation MCP server
  ([agent-skills#13](https://github.com/vaadin/agent-skills/issues/13)).
- `vaadin-add-ons` skill for finding, evaluating, and installing Vaadin
  Directory add-ons.
- Manifest metadata for the Claude and OpenAI plugin directories, and a
  square icon.

### Changed

- The bundled CLI moved from `bin/` to `cli/`. claude.ai and Cowork refuse
  plugins with a top-level `bin/` directory, and directory installs from
  claude.ai sync into Claude Code. Skills run it through its full path.
- The CLI no longer has a version of its own, so a release that changes no Go
  code doesn't need new binaries
  ([agent-tools#2](https://github.com/vaadin/agent-tools/issues/2)).
- `vaadin-frontend-design` pointed to a theming skill that doesn't exist. It now
  points to `aura-theme` and to the docs MCP's `get_theme_css_properties` and
  `get_component_styling`
  ([agent-skills#18](https://github.com/vaadin/agent-skills/issues/18)).
- Skills name MCP tools by their real names instead of `mcp_vaadin_…`.

### Fixed

- The CLI launcher found no binary under Git Bash on Windows, which Claude Code
  uses for Bash commands and hooks.
