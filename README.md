# ITTI Laravel Template Support for PhpStorm

Navigation, completion and checks for projects built on the StudioITTI Laravel template: translation keys, schema
field and `rel:` paths, slots, config registries, permissions, mail templates. What it does is specified in
`docs/SPEC.md`, shared with the VS Code extension in `../itti-laravel-vscode`.

## Features

**Translation keys** (YAML `*Key` and PHP `'…Key' =>` in `resources/lang/<locale>/*.php`; `t()` / `$t()` / `__()` in
JS/Vue in `lang/<locale>.json`, the only file the SPA reads)
- Ctrl+click any segment → that key in every locale, each row labelled `en: …` / `bg: …`
- Completion level by level
- Warnings: unknown key, missing in a locale
- Alt+Enter: add the key to the missing locales (the PHP or the JSON files)

**Schema YAML** (Model/List/EditSchema)
- Field names and `rel:` paths: Ctrl+click each segment to the relation, column, schema field or accessor; completion;
  unknown-field warnings
- `slot:` / `cells:` → the `<template #x>` in the page or a component it imports
- `list:` → route line, controller, schemas
- `tabTitle` → repeater item field
- `repeater:` without `array-item` → warning

**Config and PHP**
- `navigation.php` `route` / `active` → route declaration (prefixed and loop-built names included)
- Views, block partials, `view()` returns → Blade file
- `html-preset` → `rich-text.php`
- Block / popup `formLayout` → the entry's `fields`
- Mail template codes and placeholders → `mail-templates.php`, with checks
- `#[Restricted]` → permission map and translations, with checks (per-scope maps too)

**Icons**
- `mdi-…` anywhere: glyph in the gutter, completion with pictures, unknown-icon warning

**Navigation**
- Gutter icon "Domain of X" on models, controllers and schema files: model, schemas, controller, pages, route
- Gutter icon on Vue slot templates: the schema values that use the slot

**Find usages and rename**
- Alt+F7 from methods, lang keys, columns, placeholders, permissions, schema keys, slots → all string usages
- Shift+F6 on a relation or accessor method, or a ModelSchema field key → also rewrites the `rel:` paths and field
  names (accessors keep their snake_case form)
- Shift+F6 on lang keys (all locales), columns, placeholders → renames every usage

## Build

Needs JDK 21 (`java` on the PATH may be older):

```shell
JAVA_HOME=/path/to/jdk-21 ./gradlew test buildPlugin
```

The zip lands in `build/distributions/`; install it with *Settings → Plugins → ⚙ → Install Plugin from Disk…* and
restart PhpStorm (the version number does not change between builds).

## Installation

The plugin is not on JetBrains Marketplace. Each GitHub release carries the zip and an `updatePlugins.xml`, which
PhpStorm reads as a custom plugin repository, so it offers new releases as ordinary plugin updates:

1. *Settings → Plugins → ⚙ → Manage Plugin Repositories…*, add
   `https://github.com/StudioITTI/itti-laravel-intellij/releases/latest/download/updatePlugins.xml`
2. *Marketplace* tab → search for "ITTI Laravel" → *Install*

A copy installed from disk earlier is the same plugin (same ID) and updates from the repository from then on.

## Releasing

1. Raise `version` in `gradle.properties` and list the changes under `## [Unreleased]` in `CHANGELOG.md`.
2. Push to `main`. The Build workflow prepares a draft release with those notes.
3. Publish the draft on GitHub. The Release workflow attaches the zip and `updatePlugins.xml` to it, and opens a pull
   request moving the notes under the version's heading in `CHANGELOG.md`.


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
