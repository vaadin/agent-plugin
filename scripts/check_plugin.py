#!/usr/bin/env python3
"""Check plugin/ for the mistakes that break an install or a directory submission.

Both hosts read the same skills, hooks, and MCP servers, but each has its own
manifest, so the two manifests have to agree. The limits below are the ones
the Claude and OpenAI directories enforce at submission.

Usage: python3 scripts/check_plugin.py   (exit code 1 when anything fails)
"""

import json
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "plugin")
errors = []


def fail(message):
    errors.append(message)


def load(rel):
    with open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return json.load(f)


claude = load(".claude-plugin/plugin.json")
codex = load(".codex-plugin/plugin.json")
mcp = load(".mcp.json")
load("hooks/hooks.json")

for key in ("name", "version", "description", "license", "repository"):
    if claude.get(key) != codex.get(key):
        fail(f"'{key}' differs between the Claude and Codex manifests")

if not re.fullmatch(r"\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?", codex.get("version", "")):
    fail("version must be semver")

if codex.get("mcpServers") != "./.mcp.json":
    fail("the Codex manifest must declare mcpServers as ./.mcp.json")

for name, server in mcp.get("mcpServers", {}).items():
    url = server.get("url", "")
    if not url.startswith("https://"):
        fail(f"MCP server '{name}' must use an https:// URL")

ui = codex.get("interface", {})
limits = {"displayName": 30, "shortDescription": 30, "longDescription": 4000, "developerName": 80}
for key, limit in limits.items():
    if len(ui.get(key, "")) > limit:
        fail(f"interface.{key} is longer than {limit} characters")
prompts = ui.get("defaultPrompt", [])
if len(prompts) > 3 or any(len(p) > 128 for p in prompts):
    fail("interface.defaultPrompt allows at most 3 prompts of 128 characters")
for key in ("websiteURL", "supportURL", "privacyPolicyURL", "termsOfServiceURL"):
    if not ui.get(key, "").startswith("https://"):
        fail(f"interface.{key} must be an https:// URL")

for manifest, keys in ((codex.get("interface", {}), ("composerIcon", "logo")), (claude, ("icon",))):
    for key in keys:
        path = manifest.get(key)
        if path and not os.path.isfile(os.path.join(ROOT, path)):
            fail(f"'{key}' points to a missing file: {path}")

skills_dir = os.path.join(ROOT, "skills")
for skill in sorted(os.listdir(skills_dir)):
    skill_md = os.path.join(skills_dir, skill, "SKILL.md")
    if not os.path.isfile(skill_md):
        fail(f"skills/{skill} has no SKILL.md")
        continue
    text = open(skill_md, encoding="utf-8").read()
    front = re.match(r"---\n(.*?)\n---\n", text, re.S)
    if not front:
        fail(f"skills/{skill}/SKILL.md has no frontmatter")
        continue
    name = re.search(r"^name:\s*(.+)$", front.group(1), re.M)
    if not name or name.group(1).strip() != skill:
        fail(f"skills/{skill}/SKILL.md: name must equal the directory name")
    description = re.search(r"^description:\s*(>-?|\|-?)?\s*\n?(.*?)(?=^\w[\w-]*:|\Z)", front.group(1), re.M | re.S)
    if not description:
        fail(f"skills/{skill}/SKILL.md has no description")
    elif len(" ".join(description.group(2).split())) > 1024:
        fail(f"skills/{skill}/SKILL.md: description is longer than 1024 characters")
    if len(f"{codex['name']}:{skill}") > 64:
        fail(f"'{codex['name']}:{skill}' is longer than 64 characters")

# The Claude directory's review holds back a plugin that ships executables or
# bytecode, which is why the CLI ships as Java source. Recognize compiled code by
# extension and by its first bytes: ELF, PE, Mach-O (and Java class files, which
# share the fat Mach-O magic), WebAssembly, and ZIP-based archives such as JARs.
COMPILED_EXTENSIONS = (".class", ".jar", ".exe", ".dll", ".so", ".dylib", ".wasm", ".pyc", ".zip")
COMPILED_MAGIC = (b"\x7fELF", b"MZ", b"\xca\xfe\xba\xbe", b"\xfe\xed\xfa\xce", b"\xfe\xed\xfa\xcf",
                  b"\xce\xfa\xed\xfe", b"\xcf\xfa\xed\xfe", b"\x00asm", b"PK\x03\x04")

for dirpath, dirnames, filenames in os.walk(ROOT):
    for entry in dirnames + filenames:
        path = os.path.join(dirpath, entry)
        rel = os.path.relpath(path, ROOT)
        if os.path.islink(path):
            fail(f"symlinks are not allowed: {rel}")
        if entry == ".DS_Store":
            fail(f"remove {rel}")
    for entry in filenames:
        path = os.path.join(dirpath, entry)
        rel = os.path.relpath(path, ROOT)
        # The Claude directory rejects the plugin; `claude plugin validate` doesn't check this.
        if os.path.getsize(path) > 5 * 1024 * 1024:
            fail(f"{rel} is over the Claude directory's 5 MiB limit per file")
        with open(path, "rb") as f:
            head = f.read(4)
        if entry.lower().endswith(COMPILED_EXTENSIONS) or head.startswith(COMPILED_MAGIC):
            fail(f"{rel} is compiled code, which holds the plugin back in the Claude directory's review")
if os.path.isdir(os.path.join(ROOT, "bin")):
    fail("a top-level bin/ directory stops claude.ai and Cowork from installing the plugin")

for message in errors:
    print(f"✗ {message}")
if errors:
    sys.exit(1)
print("✓ plugin/ looks good")
