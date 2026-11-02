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

## Data and network use

- **The MCP servers** receive the requests the agent makes to them: search
  questions, component and class names, Vaadin versions, and add-on names.
  They do not receive your source code unless the agent puts it in a query.
  Both are operated by Vaadin under the
  [Vaadin Privacy Policy](https://vaadin.com/privacy-policy).
- **`vaadin-create-project`** downloads a project skeleton from
  `https://start.vaadin.com` when you ask for a new project. The request carries
  the project name, the options you chose, and a parameter that identifies the
  plugin as the source.
- **The checks and the styling hook** run on your machine. They read the
  project's `.css` and `.java` files and send nothing anywhere.
- The plugin collects no telemetry of its own.

## Troubleshooting

**The agent doesn't use the Vaadin documentation.** Run `/mcp` and check that
`vaadin` and `vaadin-directory` are connected. Both need outbound HTTPS access to
`vaadin.com`. Behind a proxy, make sure your agent's MCP client can reach them.

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
