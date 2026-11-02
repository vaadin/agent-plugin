---
name: vaadin-add-ons
description: Find, evaluate, and install third-party Vaadin add-ons from Vaadin Directory. Use when a Vaadin application needs a component, integration, or theme that Vaadin does not ship (a date range picker, a map, a QR scanner, a rich-text or code editor, a signature pad), when the user asks "is there an add-on for…", wants to compare add-ons, or wants to add an add-on dependency to a Vaadin project.
---

# Vaadin Add-ons

Find a Vaadin Directory add-on that fits the project, check that it is
compatible and maintained, and add it to the build. The Vaadin Directory MCP
tools (`directory_search`, `directory_getAddon`) answer from the live Directory,
so use them rather than recalling add-on names, coordinates, or versions from
memory.

## Workflow

### 1. Know the project

- **Vaadin version.** Read the major version from the build: the
  `vaadin.version` property or the `com.vaadin:vaadin-bom` /
  `com.vaadin:vaadin` version in `pom.xml`, or the Vaadin plugin / platform
  version in `build.gradle(.kts)`. Pass the major (`"25"`, `"24"`, …) as
  `vaadinVersion` to both Directory tools. If there is no project yet, ask which
  version the user targets rather than assuming.
- **Build tool.** Maven or Gradle decides which install snippet to use.
- **UI language.** Most Directory add-ons are Flow (Java) components. For a
  Hilla/React view, say so when an add-on is Flow-only.

### 2. Check whether Vaadin already has it

A built-in component is supported, versioned with the platform, and styled by
Aura and Lumo, so prefer it to an add-on. Before searching the Directory, check
the Vaadin docs MCP (`search_vaadin_docs`, or `get_components_by_version` for
the component list). If a built-in component covers the need, recommend it and
stop unless the user still wants an add-on.

### 3. Search the Directory

Call `directory_search` with a short query (`query`), the `vaadinVersion` from
step 1, and, when it helps, a `type` filter (`component`, `integration`,
`theme`, or `tool`). If the first query returns little, retry with synonyms or
the name of the underlying JavaScript library ("signature pad" →
"signature"; "maps" → "leaflet").

### 4. Evaluate the candidates

Call `directory_getAddon` with each promising `addonId` and the same
`vaadinVersion`. Weigh:

- **Compatibility** — `supportedVaadinVersions` includes the project's major,
  and `compatibilityConfidence` is high. Treat low confidence as unverified.
- **Maintenance** — a recent `lastReleaseDate` and a `sourceRepoUrl` with
  recent activity.
- **Adoption** — `rating` together with `ratingCount`; a 5.0 from two ratings
  says little.
- **License** — `license`. Call out commercial or copyleft licenses before
  adding the dependency, since they can matter for the user's distribution.

When more than one candidate is viable, show the user a short comparison and
let them pick. When the user already named the add-on, or only one candidate
fits, go ahead.

### 5. Add the dependency

Use the `install` block from `directory_getAddon`: `mavenSnippet` for Maven,
`gradleSnippet` for Gradle. Use the version it returns
(`latestCompatibleVersion`) and never invent one.

If `install.repository` is anything other than `maven-central`, the artifact is
served from the Vaadin add-ons repository, which the build must declare. Add it
once, if it is not already there:

```xml
<repositories>
    <repository>
        <id>vaadin-addons</id>
        <url>https://maven.vaadin.com/vaadin-addons</url>
    </repository>
</repositories>
```

```gradle
repositories {
    maven { url = uri("https://maven.vaadin.com/vaadin-addons") } // Groovy and Kotlin DSL
}
```

Then compile (`mvn compile`, or `./gradlew compileJava`) to confirm the
dependency resolves before writing code against it.

### 6. Use it

`usageSnippets` from `directory_getAddon` are often placeholders. Before writing
code against the add-on, read its own documentation: `docsUrl` when it is set,
otherwise the README in `sourceRepoUrl`. The Vaadin docs MCP's
`get_java_symbol` covers Vaadin's own API, not add-ons, so do not expect it to
know an add-on's classes; read the add-on's sources or javadoc instead of
guessing method names.

Many add-ons were styled for Lumo. If the project uses Aura, check how the
add-on renders and tell the user if it looks out of place.

## Reporting

Tell the user which add-on you chose and why (compatibility, maintenance,
license), the coordinates and version you added, whether you added the
`vaadin-addons` repository, and anything you could not verify, such as an
add-on whose compatibility confidence is low.
