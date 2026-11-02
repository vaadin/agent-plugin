---
name: vaadin-check-theme-mixing
description: Detect whether a Vaadin project mixes the Aura and Lumo base themes, which produces conflicting styles and unresolved CSS custom properties. Use when reviewing or building a Vaadin UI, switching or configuring a theme, applying theme variants, importing theme stylesheets, or when the user reports styles or --lumo-* / --aura-* custom properties not resolving.
---

# vaadin-check-theme-mixing

Run the bundled checker against a Vaadin project and report what it finds.

## Run it

The checker is a self-contained native binary bundled with this plugin. It
needs no Node.js or JVM, and it never downloads or searches the machine for a
runtime. Use `.` for `<project-dir>` if the user did not name one.

The launcher is `cli/vaadin-agent-tools` at the root of this plugin, two levels
above the directory that holds this SKILL.md. In Claude Code the plugin root is
`${CLAUDE_PLUGIN_ROOT}`, so run:

```bash
"${CLAUDE_PLUGIN_ROOT}/cli/vaadin-agent-tools" check-theme-mixing <project-dir> --json
```

In Codex, build the path from this skill's own location instead:

```bash
"<this skill's directory>/../../cli/vaadin-agent-tools" check-theme-mixing <project-dir> --json
```

On Windows without a POSIX shell, run `cli\vaadin-agent-tools.bat` with the same
arguments.

## Read the result

The JSON envelope is:

```json
{ "tool": "check-theme-mixing", "ok": true, "themesLoaded": [], "filesScanned": 0, "findings": [] }
```

Exit code: `0` = no error-level findings · `1` = mixing detected · `2` = usage
error (e.g. the directory does not exist).

Report each entry in `findings` with its `code`, `message`, and `evidence`
(each item is `file` + `line` + `snippet`). The codes:

- `MULTIPLE_BASE_THEMES` (error) — both Aura and Lumo are loaded. Keep exactly
  one base theme.
- `LUMO_UTILITY_WITHOUT_LUMO_THEME` (error) — `LumoUtility` is used while Aura is
  the loaded theme; its utility classes are undefined under Aura.
- `MISMATCHED_THEME_TOKENS` (warning) — `--<other>-*` custom properties are used
  under a different base theme and will not resolve.
- `THEME_INDETERMINATE` (info) — no base theme is explicitly loaded, so the
  theme-dependent checks were **skipped**. This is not a pass; say so, and
  suggest confirming which base theme the project loads.
- `LEGACY_THEME_ANNOTATION` (info) — a legacy `@Theme` annotation sits alongside
  `@StyleSheet`-based theming; worth confirming during a Vaadin 24→25 upgrade.

Treat `error` findings as blocking; surface `warning`/`info` findings for the
user to judge rather than failing the task on them.
