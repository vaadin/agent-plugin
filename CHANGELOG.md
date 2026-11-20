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
- In Claude Code, `VAADIN_DOCS_MCP_URL` points the `vaadin` documentation
  server at a self-hosted one, for networks that can't reach `vaadin.com`
  ([#3](https://github.com/vaadin/agent-plugin/issues/3)).

### Changed

- The bundled CLI is one Java source file, `cli/VaadinAgentTools.java`, that
  the JDK runs directly (`java VaadinAgentTools.java`), instead of a native
  binary for each platform. The plugin ships no compiled code, which the Claude
  plugin directory's review holds back, and the tool runs anywhere with a JDK
  17 or later: the one in `JAVA_HOME`, or else the one the `java` on the `PATH`
  belongs to. Without one, the skills say what's missing and the styling hook
  does nothing.
- The styling hook starts Java only for edits that could touch styling: `.css`
  files, and `.java` edits that mention a styling API.
- The bundled CLI moved from `bin/` to `cli/`. claude.ai and Cowork refuse
  plugins with a top-level `bin/` directory, and directory installs from
  claude.ai sync into Claude Code. Skills run it through its full path.
- The CLI no longer has a version of its own; it ships with, and is versioned
  as, the plugin
  ([agent-tools#2](https://github.com/vaadin/agent-tools/issues/2)).
- Skills name MCP tools by their real names instead of `mcp_vaadin_…`.
- `vaadin-create-project` downloads the skeleton with `curl`, and the CLI's
  `create-project` only extracts it (`--skeleton=<file.zip>`; `--name`,
  `--example`, and `--pre` became URL parameters in the skill). The CLI no
  longer accesses the network.
- Requests to start.vaadin.com identify the source as `ref=agent-plugin`
  instead of `ref=agent-tools`.

### Fixed

- The CLI didn't run under Git Bash on Windows, which Claude Code uses for Bash
  commands and hooks.
