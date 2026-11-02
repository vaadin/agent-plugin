# Vaadin plugin for Claude Code and Codex

This repository builds the Vaadin plugin for coding agents: one plugin for
Claude Code and Codex with the Vaadin documentation MCP server, the Vaadin
Directory MCP server, Vaadin development skills, and a bundled tool that checks
a project's theme usage.

**To install or use the plugin, see [plugin/README.md](plugin/README.md).** It is
published in the Claude and OpenAI plugin directories, which is the recommended
way to install it.

The plugin replaces two earlier plugins, whose sources were merged here:

- [`vaadin/agent-skills`](https://github.com/vaadin/agent-skills)
  (`vaadin-skills`): the docs MCP server and the theming, design, form, testing,
  and review skills
- [`vaadin/agent-tools`](https://github.com/vaadin/agent-tools)
  (`vaadin-agent-tools`): the native CLI, its skills, and the styling hook

## Repository layout

| Path | Contents |
| --- | --- |
| [`plugin/`](plugin) | The plugin itself, exactly as users install it |
| [`go/`](go) | Source of the `vaadin-agent-tools` CLI bundled in `plugin/cli/` |
| [`test/fixtures/`](test/fixtures) | Sample projects for the CLI tests |
| [`tools/aura-reference/`](tools/aura-reference) | Generator for the Aura property tables in the `aura-theme` skill |
| [`scripts/`](scripts) | Packaging and maintenance scripts |
| `.claude-plugin/`, `.agents/plugins/` | Development marketplaces for testing from a checkout |

See [CONTRIBUTING.md](CONTRIBUTING.md) to build, test, and release the plugin.

## License

[Apache License 2.0](LICENSE)
