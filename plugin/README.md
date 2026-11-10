# Vaadin

Build [Vaadin](https://vaadin.com) applications with Claude Code or Codex. The
plugin gives your coding agent current Vaadin documentation and Java API
lookups, search across the add-ons in Vaadin Directory, skills for setting up
projects, theming, visual design, form layouts, browserless UI tests, and code
review, and automatic checks that catch theme mistakes the browser would
silently ignore.

Vaadin adds API faster than models are retrained. With this plugin the agent
looks things up in the documentation for your Vaadin version instead of
guessing from what it remembers.

## What's included

### MCP servers

| Server | URL | What it gives the agent |
| --- | --- | --- |
| `vaadin` | `https://mcp.vaadin.com/docs` | Search of the Vaadin documentation by version and UI language, component Java, React, and web component APIs, styling and theme properties, Java symbol lookup, Maven coordinates, and the APIs new in each release. |
| `vaadin-directory` | `https://vaadin.com/directory/mcp` | Search of the add-ons in [Vaadin Directory](https://vaadin.com/directory), with compatibility, ratings, licenses, and install snippets. |

Both are hosted by Vaadin and need no account or API key.

### Skills

The agent picks up a skill when your request matches it. You can also name one.

| Skill | Use it to |
| --- | --- |
| `vaadin-create-project` | Create a new Vaadin project from [start.vaadin.com](https://start.vaadin.com). |
| `vaadin-form-layout` | Build a form or entity editor with `FormLayout` from a Figma link, screenshot, or description. |
| `aura-theme` | Create or adjust an Aura theme: colors, typography, density, surfaces, dark mode. |
| `vaadin-frontend-design` | Make a view look polished and distinctive, beyond the default theme. |
| `vaadin-browserless-testing` | Write browserless tests that verify use cases inside the JVM and read as the use case itself. |
| `vaadin-usage-review` | Review Vaadin code for workarounds where Vaadin already has an API or component. Reports only; never edits. |
| `vaadin-add-ons` | Find, compare, and install add-ons from Vaadin Directory. |
| `vaadin-check-theme-mixing` | Check whether a project mixes the Aura and Lumo themes. |
| `vaadin-check-aura-usage` | Check a project's CSS and Java inline styles for Aura custom properties that do nothing. |

### Automatic styling checks

After the agent edits a `.css` file, or a `.java` file in a way that touches
Vaadin styling, the plugin runs the theme-mixing and Aura-usage checks on that
project. It stays silent unless it finds an error, and then tells the agent what
to fix. It never blocks an edit.

In Codex, the checks run only when you install the plugin from the Vaadin
marketplace (see [Install](#install)), because OpenAI's plugin directory doesn't
accept plugins with hooks.

## Install

### Claude Code

Install **Vaadin** from the plugin directory: run `/plugin`, open **Discover**,
and search for Vaadin. Plugins you add on claude.ai under **Customize > Plugins**
also reach Claude Code.

### Codex

Install **Vaadin** from the Plugins Directory in the ChatGPT desktop app.

To get the automatic styling checks as well, install it from the Vaadin
marketplace instead:

```shell
codex plugin marketplace add vaadin/agent-marketplace
codex plugin add vaadin@vaadin-marketplace
```

### Moving from `vaadin-skills` and `vaadin-agent-tools`

This plugin replaces both. Uninstall them so their skills and MCP server don't
load twice:

```shell
/plugin uninstall vaadin-skills@vaadin-marketplace
/plugin uninstall vaadin-agent-tools@vaadin-marketplace
```

If you installed them from the Claude plugin directory instead, remove them on
claude.ai under **Customize > Plugins**, or in Claude Code from `/plugin` >
**Installed**.

In Codex, use `codex plugin remove` with the same names.

## Try it

- "Create a new Vaadin project in ./inventory-app."
- "Build an editor for the `Customer` entity with sections for contact details and address."
- "Give this app a dark Aura theme with emerald accents and a compact layout."
- "Check the current Vaadin docs and add lazy loading to this Grid."
- "Write browserless tests for the booking use cases of this view."
- "Review the code you just wrote for Vaadin workarounds."
- "Is there a Vaadin add-on for a date range picker that works with our Vaadin version?"
- "My `--aura-background-color` change broke dark mode. What's wrong?"

## Requirements

- Claude Code or Codex.
- A JDK and Maven or Gradle to build and run Vaadin projects. The plugin itself
  needs no Java, Node.js, or other runtime.
- For the checks and `vaadin-create-project`: macOS (Apple silicon or Intel),
  Linux (x64 or arm64), or Windows (x64). They use a native tool bundled with the
  plugin.
- For `vaadin-create-project`: `curl`, which comes with macOS, Windows 10 and
  later, and most Linux distributions.

## Data and network use

- **The MCP servers** receive the requests the agent makes to them: search
  questions, component and class names, Vaadin versions, and add-on names.
  They do not receive your source code unless the agent puts it in a query.
  Both are operated by Vaadin under the
  [Vaadin Privacy Policy](https://vaadin.com/privacy-policy).
- **`vaadin-create-project`** has the agent download a project skeleton from
  `https://start.vaadin.com` with `curl` when you ask for a new project. The
  request carries the project name, the options you chose, and a parameter that
  identifies the plugin as the source. The bundled tool then extracts it.
- **The checks and the styling hook** run on your machine. They read the
  project's `.css` and `.java` files and send nothing anywhere.
- **The bundled tool** (`cli/`) never accesses the network.

## Using your own documentation server

On a network that can't reach `vaadin.com`, such as an air-gapped one, you can
run your own Vaadin documentation MCP server and point the plugin at it. The
skills call the MCP tools by name, so they work with any server that has the
same tools, such as `search_vaadin_docs`, `get_java_symbol`, and
`get_new_apis`.

### Claude Code

Block the hosted servers in your settings (`~/.claude/settings.json`), or in
managed settings for a whole organization:

```json
{
  "deniedMcpServers": [
    { "serverUrl": "https://mcp.vaadin.com/*" },
    { "serverUrl": "https://vaadin.com/directory/*" }
  ]
}
```

Then add your own server:

```shell
claude mcp add --transport http --scope user vaadin https://docs-mcp.example.internal/docs
```

`claude mcp list` should show your `vaadin` server, and no longer show
`plugin:vaadin:vaadin` or `plugin:vaadin:vaadin-directory`.

### Codex

In `~/.codex/config.toml`, add your server under the name `vaadin`, which
replaces the plugin's server of the same name, and turn off the Directory
server:

```toml
[mcp_servers.vaadin]
url = "https://docs-mcp.example.internal/docs"

[plugins."vaadin@<marketplace>".mcp_servers.vaadin-directory]
enabled = false
```

Replace `<marketplace>` with the marketplace you installed the plugin from, as
`codex plugin list` shows it, and run `codex mcp list` to check. Codex doesn't
document that a server in `config.toml` replaces a plugin's server of the same
name; this was checked with Codex 0.163.

### What still needs `vaadin.com`

- **`vaadin-add-ons`** searches the hosted Vaadin Directory server.
- **`vaadin-create-project`** downloads the project skeleton from
  `https://start.vaadin.com`.
- **Installing** from the Claude and OpenAI plugin directories or from GitHub.
  Instead, mirror [vaadin/agent-plugin](https://github.com/vaadin/agent-plugin)
  on a Git host or shared folder on your network and list its `plugin/` folder
  in a marketplace of your own. For Claude Code, see
  [Plugins for organizations](https://code.claude.com/docs/en/plugins/org); for
  Codex, add the marketplace with `codex plugin marketplace add <path or Git URL>`.

## Source of the bundled tool

The checks, the styling hook, and `vaadin-create-project` run
`vaadin-agent-tools`, a native tool in `cli/platform/`. It's built from the Go
source in [`go/`](https://github.com/vaadin/agent-plugin/tree/main/go) in the
plugin's repository. The build is reproducible: rebuilding a commit with
[`go/build.sh`](https://github.com/vaadin/agent-plugin/blob/main/go/build.sh)
gives byte-identical binaries, and CI checks that the committed binaries match
the source.
- The plugin collects no telemetry of its own.

## Troubleshooting

**The agent doesn't use the Vaadin documentation.** Run `/mcp` and check that
`vaadin` and `vaadin-directory` are connected. Both need outbound HTTPS access to
`vaadin.com`. Behind a proxy, make sure your agent's MCP client can reach them.
Without access to `vaadin.com`, see
[Using your own documentation server](#using-your-own-documentation-server).

**The skills or MCP server show up twice.** Uninstall `vaadin-skills` and
`vaadin-agent-tools` (see above), or remove an MCP server named `vaadin` that you
added by hand.

**"no bundled binary for …"** The checks and `vaadin-create-project` support the
platforms listed under [Requirements](#requirements). The documentation, add-on,
theming, design, and form skills work everywhere.

**The styling hook doesn't run in Codex.** Hooks run only for plugins installed
from a marketplace, and Codex asks you to review and trust a plugin's hooks before
they run. Check `/hooks`.

**A check reports something you disagree with.** Errors are what the browser
discards. Warnings are heuristics, and an unusual selector can be a false
positive. [Open an issue](https://github.com/vaadin/agent-plugin/issues) with
the snippet.

## Support

- Bugs and ideas: [github.com/vaadin/agent-plugin/issues](https://github.com/vaadin/agent-plugin/issues)
- Questions about Vaadin: [vaadin.com/forum](https://vaadin.com/forum)
- Documentation: [Agentic development with Vaadin](https://vaadin.com/docs/latest/building-apps/agentic-development)

## License

[Apache License 2.0](LICENSE)
