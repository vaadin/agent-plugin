---
name: vaadin-create-project
description: Bootstrap a new Vaadin project by downloading a fresh skeleton from start.vaadin.com and extracting it into a directory. Use when the user asks to create, scaffold, bootstrap, or start a new Vaadin application or project. Mirrors `npm init vaadin` / create-vaadin.
---

# vaadin-create-project

Create a new Vaadin project from the official start.vaadin.com skeleton, in two
steps: download the skeleton ZIP with `curl`, then extract it with the tool
bundled with this plugin.

**This reaches the network** (`https://start.vaadin.com`) and **writes files**
into the target directory. Only do it when the user has asked to create a
project, and confirm the target directory with them if it is ambiguous.

## 1. Download the skeleton

Build the URL from these query parameters:

- `artifactId=<id>` — the Maven artifactId. Use the name the user gave, or the
  target directory's name, keeping only letters, digits, `-`, and `_`.
- `frameworks=flow` — include the "Task List" Flow example view. Leave it out
  for an empty project.
- `platformVersion=pre` — only if the user asks for the pre-release Vaadin
  version.
- `ref=agent-plugin` — always; it tells Vaadin the download came from this
  plugin.

Download it to a temporary file:

```bash
curl -fsSL -o "${TMPDIR:-/tmp}/vaadin-skeleton.zip" "https://start.vaadin.com/skeleton?artifactId=my-app&frameworks=flow&ref=agent-plugin"
```

In PowerShell, call `curl.exe`, not `curl`, and save to `$env:TEMP` instead.

If `curl` fails with HTTP 404, start.vaadin.com doesn't know one of the
parameters; check them against the list above.

## 2. Extract it

The bundled tool is a self-contained native binary. It needs no Node.js or JVM
and doesn't access the network.

The launcher is `cli/vaadin-agent-tools` at the root of this plugin, two levels
above the directory that holds this SKILL.md. In Claude Code the plugin root is
`${CLAUDE_PLUGIN_ROOT}`, so run:

```bash
"${CLAUDE_PLUGIN_ROOT}/cli/vaadin-agent-tools" create-project <target-dir> --skeleton=<file.zip> --json
```

In Codex, build the path from this skill's own location instead:

```bash
"<this skill's directory>/../../cli/vaadin-agent-tools" create-project <target-dir> --skeleton=<file.zip> --json
```

On Windows without a POSIX shell, run `cli\vaadin-agent-tools.bat` with the same
arguments.

Flags:

- `--skeleton=<file.zip>` — the ZIP you downloaded (required).
- `--overwrite` — if `<target-dir>` exists and is non-empty, replace its
  contents. Only pass it when the user has agreed to that.

Example — extract into `./my-app`, then delete the download:

```bash
"${CLAUDE_PLUGIN_ROOT}/cli/vaadin-agent-tools" create-project ./my-app --skeleton="${TMPDIR:-/tmp}/vaadin-skeleton.zip" --json
rm "${TMPDIR:-/tmp}/vaadin-skeleton.zip"
```

## Read the result

The JSON envelope reports what was created:

```json
{ "tool": "create-project", "ok": true, "directory": "…",
  "skeleton": "…/vaadin-skeleton.zip", "files": 77 }
```

Exit code: `0` = created · `1` = the skeleton could not be read or extracted
(see the `error` field; usually the download failed) · `2` = usage error
(missing target directory or `--skeleton`, or the target already exists and is
non-empty without `--overwrite`). A ZIP that can't be read never clears the
target directory, even with `--overwrite`.

On success, tell the user the project is ready and how to run it:

```bash
cd <target-dir> && mvn
```
