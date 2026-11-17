# ITTI Laravel Template Support — Feature Specification

Version 1.0 · 2026-11-17 · describes the PhpStorm plugin as of commit `3eaf289`

This document specifies what the editor extension does, independently of any editor platform. It is written so the
same features can be rebuilt for another editor (VS Code first). Section 11 maps the concepts onto VS Code APIs;
everything before it is platform-neutral and normative.

---

## 1. Purpose

Projects built on the StudioITTI Laravel template (`main` and its project branches) describe much of their admin in
strings: schema YAML, PHP config "registries", translation calls in Vue. Generic Laravel tooling cannot see what those
strings refer to. The extension makes them behave like code:

- **Go to definition** on each string (and on each *segment* of a dotted string).
- **Completion** of valid values.
- **Diagnostics** for values that point nowhere.
- **Find references** and **rename** from the things the strings point at, back to the strings.
- **Navigation markers** (gutter icons / code lenses) between the files of one domain.
- **One quick fix**: add a missing translation key.

## 2. Terminology

| Term | Meaning |
|---|---|
| **Host string** | A string value in a source file that the extension treats as a reference: a YAML scalar, a PHP string literal, a JS/TS string literal, an HTML/Vue attribute value. |
| **Reference** | A range inside a host string plus a rule that computes its targets. A host string may carry several (one per segment). |
| **Segment** | One part of a dotted value (`admin.common.created_by` has three). Each segment is its own reference with its own range. |
| **Target** | A location a reference resolves to: a file, or a range in a file. A reference may resolve to several targets (e.g. one per locale). |
| **Declaration string** | A string that *is* the definition of something (a lang key, a migration column…). It is the starting point for find-references and rename. |
| **Project root** | The nearest ancestor directory of the file containing a file named `artisan`. All paths below are relative to it. |
| **Model** | An Eloquent model class, as found by §4.2. |
| **Domain** | A directory holding a model, usually with `ModelSchema.yaml` / `ListSchema.yaml` / `EditSchema.yaml` beside it. |
| **Severity** | `warning` or `weak warning` (a hint-level diagnostic, styled less prominently). |

General rules for all references:

- References are **soft**: an unresolved reference never produces an error by itself; only the diagnostics listed in
  this spec are reported.
- Whenever completion is triggered, the text typed so far may contain the editor's completion placeholder; matching
  must tolerate it.
- Typing `.` (or the `:` of `rel:`) inside a host string that carries references re-triggers completion.
- If a file is outside any project root, nothing applies.

## 3. Project layout assumptions

All are conventions of the template; none are configurable in v1.

| What | Where |
|---|---|
| Lang files | `resources/lang/<locale>/<group>.php` and `lang/<locale>/<group>.php`. Locale directories named `vendor` are skipped. JSON lang files `<locale>.json` directly in those two directories. |
| Locale order | `en` first, then alphabetical. |
| Migrations | `database/migrations/**/*.php` (recursive — project branches keep `database/migrations/tenant/`). |
| Routes | `routes/**/*.php` |
| Blade views | `resources/views/**/*.blade.php`, named by dotted path without extension (`public/blocks/hero.blade.php` → `public.blocks.hero`). |
| Vue | `resources/js/**/*.vue`; Inertia pages under `resources/js/Pages/`. |
| Config | `config/<name>.php`, each returning an array. |
| Permission maps | `database/permissions.php`, or per scope (see §4.10). |
| Material Design Icons | `node_modules/@mdi/font/scss/_variables.scss` and `node_modules/@mdi/font/fonts/materialdesignicons-webfont.ttf`. |
| CRUD base controller | `\App\Http\Controllers\BaseInertiaCRUDController` |
| Schema files | `ModelSchema.yaml`, `ListSchema.yaml`, `EditSchema.yaml` |

---

## 4. Shared resolvers (the data model)

Every feature is built on the resolvers below. They read PHP/YAML/Vue *syntax* (not runtime state), and must be
cached and invalidated on file changes (§12).

### 4.1 Config values

`configValue('a.b.c')`: open `config/a.php`, take the array it `return`s, walk keys `b`, then `c`. Returns the
expression found (array, string, class constant, call…), or nothing. Array keys are compared as string literal contents.

### 4.2 Model of a schema file

The model a schema YAML belongs to is the first non-interface, non-trait class **in a PHP file in the same directory**
that implements an interface whose name ends with `\IHasModelSchema` or uses a trait whose name ends with
`\HasModelSchema`. (This mirrors the runtime rule.)

### 4.3 Model introspection

**Hierarchy.** The class, its traits (recursively) and its parent classes, stopping at anything under `\Illuminate\`.
Methods are collected across the hierarchy, de-duplicated case-insensitively by name.

**Relations.** A non-static method whose parameters are all optional and whose body contains a call
`$this-><kind>(…)` where `<kind>` is one of: `hasOne`, `hasMany`, `belongsTo`, `belongsToMany`, `morphOne`,
`morphMany`, `morphTo`, `morphToMany`, `morphedByMany`, `hasOneThrough`, `hasManyThrough`.

**Related model.** The first argument of that relation call, resolved to a class name by following, up to 6 steps deep:

| Expression | Followed to |
|---|---|
| `X::class` | X (resolved through `use` imports) |
| `'App\\Foo'` string | that class name |
| `$this->method()` | the `return` expressions of that method on the same model (hierarchy-wide) |
| `config('a.b')` | the config value (§4.1) |
| `$var` | the values assigned to `$var` in the enclosing function |

Example: `HasUserstamps::creator()` → `$this->belongsTo($this->userstampsModel())` → `return config('userstamps.model')`
→ `User::class`.

**Table name.** The model's `$table` property default value if it is a string. Otherwise snake_case the class name and
pluralise its last word (rules: uncountables `news data media metadata equipment information feedback series species`
stay; `person→people`, `child→children`; consonant+`y` → `ies`; `s x z ch sh` → `+es`; else `+s`). Use the plural if a
migration creates that table, else the singular if one does, else the plural.

**Fields** — every name a field path may use on a model, each with all of its sources:

| Source | Name | Target |
|---|---|---|
| ModelSchema | each key under top-level `fields:` | the YAML key |
| Migrations | each column of the model's table (§4.4) | the column's declaring expression |
| Legacy accessor | `getFooBarAttribute()` | `foo_bar` | the method |
| Modern accessor | a method whose return type is `Attribute` (optionally nullable / namespaced), e.g. `fullName(): Attribute` | `full_name` | the method |

snake_case: insert `_` before each capital that follows a lowercase letter or digit, then lowercase.

**Nested fields** (json columns): a ModelSchema field whose value is a mapping with its own `fields:` mapping.

### 4.4 Migration columns

Scan every migration file for `Schema::create('<table>', fn)` and `Schema::table('<table>', fn)`, where `fn` is a closure
whose first parameter is the blueprint variable. Inside, each `$blueprint->method(...)` call contributes columns:

| Call | Columns (target) |
|---|---|
| `bigIncrements binary boolean char date dateTime dateTimeTz decimal double enum float foreignId foreignUlid foreignUuid geography geometry increments integer ipAddress json jsonb longText macAddress mediumIncrements mediumInteger mediumText set smallIncrements smallInteger string text time timeTz timestamp timestampTz tinyIncrements tinyInteger tinyText unsignedBigInteger unsignedInteger unsignedMediumInteger unsignedSmallInteger unsignedTinyInteger ulid uuid year vector bigInteger` with first arg `'c'` | `c` (the string) |
| `id()` / `id('c')` | `id` / `c` |
| `renameColumn('a', 'b')` | `b` |
| `timestamps() timestampsTz() nullableTimestamps()` | `created_at`, `updated_at` (the call) |
| `softDeletes()` / `softDeletes('c')`, `softDeletesTz` | `deleted_at` or `c` |
| `rememberToken()` | `remember_token` |
| `morphs('x')` and the `nullable`/`uuid`/`ulid` variants | `x_id`, `x_type` |
| `userstamps()` (project macro) | `created_by`, `updated_by` (the call) |

Files are processed in file-name order across all subdirectories (dated names keep migration order). A table name used
on both sides of a multi-tenant project merges its columns — accepted.

### 4.5 Lang keys

A key `g.k1.k2…` resolves, per locale directory, to the group file `g.php` and then to nested array entries `k1`, `k2`…
The resolver returns one node per locale where the prefix exists. A node is either the group file or an array entry; its
**text** is the entry's string value, if it is a string.

- **Variants** for completion after a prefix: the union across locales of the keys at that level. A key whose value is an
  array is shown as a branch (`{…}`) and, when accepted, appends `.` and re-opens completion; a leaf shows its text.
- **Group variants** (first segment): the union of `*.php` file names across locale directories.
- **JSON keys**: a key that appears verbatim (as a JSON string, `"the.key"`) in any `<locale>.json` counts as known.

### 4.6 Route index

Built from every PHP file under `routes/`.

1. **Named routes.** Every `->name('n')` method call is a route `n` — unless that call is chained further into a
   `->group(...)` call, in which case it is a *group prefix*, not a route.
2. **Resource routes.** Every call to the function `itti_resource_route('r', Ctrl::class [, ['index', 'edit', …]])`:
   - registers resource `r` (target: the `'r'` string);
   - adds routes `r.index r.create r.store r.show r.edit r.update r.destroy`, or only the actions listed in the third
     argument; plus `r.exportList`.
3. **Group name prefixes.** A route's name is prefixed with every enclosing group prefix, outermost first:
   - `->name('x.')` or `->as('x.')` anywhere earlier in the chain of a `->group(closure)`;
   - `'as' => 'x.'` in an array passed as the first argument of `group(...)`;
   - **file mounting:** a string literal ending in `.php` that names a file under `routes/` (e.g.
     `group(base_path('routes/central.php'))`, or `require __DIR__.'/admin.php'` inside a group closure) gives that whole
     file the prefix of the groups around the literal.
4. **Names built in loops.** A double-quoted name containing `$var` or `{$var}` is expanded using the enclosing
   `foreach (<array> as [$k =>] $v)`:
   - `$v` over `array_keys(X)` → the keys of X;
   - `$v` over `X` → the string values of X;
   - `$k` over `X` → the keys of X;
   - where X is a literal array or `config('…')` (§4.1).
   Several variables expand as a cross product. If any variable cannot be listed, the name becomes a **pattern** in
   which each variable matches exactly one segment (`[^.]+`). Looking up a route name tries exact names first, then
   patterns. Patterns are never offered in completion.

### 4.7 Blade views

Map of dotted view name → file (§3).

### 4.8 Vue slot index

For each `.vue` file under `resources/js`:

- **Slots filled**: every `<template>` element carrying an attribute `#name` or `v-slot:name` (name matches
  `[A-Za-z_][\w.-]*`; dynamic `#[name]` is ignored) **whose parent element is a direct child of the file's root
  `<template>`**. Deeper `<template #x>`s fill inner widgets' slots and are excluded.
- **Imports**: every `import X from "<relative path>.vue"`, resolved against the file's directory.
- **Reachable set**: a set of files plus everything they import, transitively.

### 4.9 Domains

- **CRUD controller**: a class whose ancestor chain includes the base controller (§3).
- **Controller → model**: the declared type of the constructor's first parameter.
- **Model → controllers**: all CRUD controllers whose model is it.
- **Controller → pages**: every string literal in the controller matching `^[A-Z][A-Za-z0-9_]*/[A-Za-z0-9_/]+$`
  (e.g. `Admin/Users/Edit`, `Central/AdminUsers/Edit`) for which `resources/js/Pages/<literal>.vue` exists.
- **Model → schema files**: those of §3 present in the model's directory.
- **Controller → route line**: the `itti_resource_route` resource whose call mentions that controller class.

### 4.10 Permission sets

`#[Restricted('perm', label?, scope: 's')]` (attribute class name ends with `\Restricted`):

- **Effective permission**: on a class, `perm`. On a method, `<classScope>@<perm>`, where `<classScope>` is the first
  string argument of the class's own `#[Restricted]`, or the class FQN without the leading `\` if it has none.
- **Scope**: the literal `scope:` argument; a method without one inherits the class's.
- **Single set** (no `output` in `config/permissions.php`): map file `database/permissions.php`; translation
  `admin.permissions.<perm>`.
- **Per scope** (`config('permissions.output')` is an array): scope defaults to `config('permissions.default_scope')`;
  the map file is `output[scope]` (`database_path('x')` → `database/x`, `base_path('x')` → `x`, also `config_path`,
  `storage_path`, or a plain string); translation `admin.permissions.<scope>.<perm>`.

### 4.11 Mail templates

`config/mail-templates.php` returns `code => entry`; an entry's `placeholders` is an array keyed by placeholder name.

### 4.12 Icons

Parse `_variables.scss` for entries `"name": HEX` → name ↔ codepoint, and remember each entry's offset (the definition
target). Optionally render a glyph from the TTF for previews. If the files are missing, icon features are silent (no
diagnostics).

---

## 5. Where references occur

Rules are evaluated in the order given; the first that applies wins for that host string.

### 5.1 YAML (any `.yaml` file)

| # | Condition | Reference kind (§6) |
|---|---|---|
| Y1 | value of a key matching `^[a-z]+Key$` (`labelKey`, `titleKey`, `hintKey`, `textKey`, `htmlKey`…) | Lang key |
| Y2 | value matching `^mdi-[a-z0-9-]*$` | Icon |
| Y3 | value of key `html-preset` | Rich-text preset |

The following apply **only in schema files** (§3) and only when §4.2 finds a model. *Path* is the list of keys from the
document root to the value, with `[]` for a sequence item. *In layout* means the path starts with `edit` or `create`,
then `formLayout`, has ≥ 3 entries and does not contain `props`.

| # | Condition | Kind |
|---|---|---|
| Y4 | in layout, not inside `item`, last path entry `repeater` | Field path **+** repeater check (§6.13) |
| Y5 | path is `list.columns.[]`, `list.columns.[].field`, `list.search.[]`, `list.totals.[].field`, `list.filters.<any>.[]`, `list.filters.<any>.[].field`; or in layout, not inside `item`, last entry `[]` (bare item) or `field` | Field path |
| Y6 | in layout, last entry `slot` or `emptySlot`, or the value of any key inside a `cells:` mapping | Slot |
| Y7 | in layout, last entry `list` | Resource |
| Y8 | in layout, last entry `tabTitle` | Repeater item field, using the sibling `repeater:` value |
| Y9 | path `order.field` or `order.scope.[]` | Field path |
| Y10 | path `fields.<f>.rel.name` | Relation name |
| Y11 | path `fields.<f>.rel.title_field`, `.value_field`, or an item of `.attributes`, `.search-attributes`, `.sort-attributes` | Field path on the **related** model (relation from the nearest enclosing `rel.name`) |

### 5.2 PHP (string literals)

| # | Condition | Kind |
|---|---|---|
| P1 | a declaration string (§8.1) | Declaration (self) |
| P2 | value matching `^mdi-[a-z0-9-]*$` | Icon |
| P3 | the first string argument of a `#[Restricted]` attribute | Permission (§4.10) |
| P4 | in `config/mail-templates.php`, a string containing `{{ name }}` | Mail placeholder, one reference per `{{ … }}` (name range only), against that entry's placeholders |
| P5 | first argument of a call named `render`, `renderPreview`, `isEnabled`, `forCode` or `definition` that resolves to a method of a class named `MailTemplateRenderer` or `EmailTemplate`; or a string assigned to a variable that is later passed as that first argument | Mail template code |
| P6 | key of an array passed as the **second** argument of such a `render(code, [...])` call, when `code` is a literal or a variable assigned one | Mail placeholder of that template |
| P7 | value of an array key matching `^[a-z]+Key$` | Lang key |
| P8 | in a file named `navigation.php`, value of key `route` | Route name |
| P9 | in a file named `navigation.php`, value of key `active`, or an item of a list under `active` | Route pattern |
| P10 | value of key `view` containing `.` | Blade view |
| P11 | value of key `partial` in `cms-blocks.php` / `cms-popups.php` | Blade view with prefix `public.blocks.` / `public.popups.` |
| P12 | value of key `html-preset` | Rich-text preset |
| P13 | the returned string of a method named `view` | Blade view |
| P14 | a field name in a registry `formLayout` (see below) | Registry field |

P5 checks the method name before resolving the call, so string arguments of unrelated calls cost nothing.

**P14 registry layout.** Walk up the string's enclosing array keys: if one is `props` or `item`, stop (no reference).
The first enclosing key `formLayout` identifies an entry (the array holding it); the entry must have a `fields` array.
The string qualifies if it is a bare list item, or the value of key `field` or `repeater`. It resolves against the
entry's `fields` keys.

### 5.3 JavaScript / TypeScript / Vue script and template expressions

| # | Condition | Kind |
|---|---|---|
| J1 | a quoted string matching `^mdi-[a-z0-9-]*$` | Icon |
| J2 | the first argument of a call whose callee name is `t`, `$t`, `tc`, `$tc`, `te`, `$te`, `trans`, `trans_choice` or `__` | Lang key |

### 5.4 HTML / Vue template attributes

| # | Condition | Kind |
|---|---|---|
| H1 | an attribute value matching `^mdi-[a-z0-9-]*$` (e.g. `icon="mdi-home"`) | Icon |

---

## 6. Reference kinds

Each kind defines: **targets**, **completion**, **diagnostics** (exact messages; `<x>` is substituted).

### 6.1 Lang key

Not applied when the value is empty or contains `::`, whitespace, `/`, `$` or `{`.

- One reference per non-empty segment. Segment *i* resolves to the nodes of the prefix `segments[0..i]` in every
  locale (§4.5): the first segment to each locale's group file, later ones to the array key in each locale.
- Completion: group names for the first segment, then the variants of the prefix.
- Diagnostics — only on the last segment, only when there are ≥ 2 segments and none is empty; range spans the whole key:
  - no locale has it, and it is not a JSON key: **warning** `Unknown translation key '<key>'`
  - some locales lack it: **weak warning** `Translation '<key>' missing in: <locales, comma-separated>`
  - both offer the quick fix of §10.
- Multi-target presentation: `<locale>: <text>` (or `<locale>: <file name>` for a group), location = file path (§9).

### 6.2 Field path

Applies to a value without whitespace. A leading `rel:` switches to relation mode; the prefix is not part of any range.
Segments are split on `.`; each is a reference.

Walk, starting at the model:

- **Field mode** (no `rel:`): segment 0 is a field (§4.3). Its targets are all its sources. Later segments are a json
  path: resolved only against declared nested fields; no diagnostics.
- **Relation mode** (`rel:a.b.c`): each segment except the last is a relation; moving through it switches the current
  model to the related model. The last segment may be a relation or a field. Once a field is reached, the rest is a
  json path.
- **Relation-name mode** (Y10): the single segment must be a relation.
- After an unknown segment or an unresolvable related model, later segments resolve to nothing and report nothing.

Diagnostics (**warning**, on the segment):
- `Unknown relation '<s>' on <Model>` (relation mode, first segment, or relation-name mode)
- `Unknown field or relation '<s>' on <Model>` (relation mode, later segment)
- `Unknown field '<s>' on <Model>` (field mode)

Completion: relations (with the related model's name as type; accepting one appends `.` and re-opens completion,
except in relation-name mode) and, where a field is possible, fields (type: their source kinds, e.g. `schema, column`).
In a json path: the declared nested field names.

### 6.3 Slot

Targets: slots named exactly the value (§4.8) in the files reachable from the **pages of the domain's controllers**
(§4.9). If those files fill no slots at all (or the domain has no known pages), fall back to every slot in
`resources/js`.
Completion: the candidate slot names. Diagnostic: **warning** `No <template #<value>> in this domain's pages`.
Presentation: `#<name>`, location = file path.

### 6.4 Resource (`list:` in a layout)

Targets: the `itti_resource_route` string (§4.6), plus the controller class named by its second argument, plus that
controller's model's schema files. Completion: resource names. Diagnostic: **warning**
`Unknown resource '<value>' (no itti_resource_route)`.

### 6.5 Repeater item field (`tabTitle`)

Resolves against `fields.<repeater>.array-item.fields` in the model's ModelSchema, where `<repeater>` is the sibling
`repeater:` value. Diagnostic (only when that mapping exists): **warning**
`Repeater '<repeater>' items have no field '<value>'`.

### 6.6 Route name / route pattern

- Route name: the route's declaring string (§4.6, exact then pattern). Completion: all listed route names. Diagnostic:
  **warning** `Unknown route '<value>'`.
- Route pattern: a value not ending in `.*` behaves as a route name. `x.*`: the reference covers `x` only and resolves
  to resource `x`, else to the first route whose name starts with `x.`. Completion: resource names. Diagnostic:
  **warning** `No route matches '<value>'`.

### 6.7 Blade view

Target: the view file (with the prefix applied). Completion: view names under the prefix, shown without it.
Diagnostic unless the value contains `::`: **warning** `View '<prefix+value>' not found`.

### 6.8 Rich-text preset

Target: the key in `config('rich-text.presets')`. Completion: preset names. Diagnostic: **warning**
`Unknown rich-text preset '<value>' (config/rich-text.php)`.

### 6.9 Icon

Target: the icon's entry in `_variables.scss`. Completion: all icon names, each with its glyph. A glyph preview is shown
in the gutter/margin of the line, tooltip = icon name. Diagnostic (only if the icon index exists): **warning**
`Unknown icon '<value>'`.

### 6.10 Permission

Targets: the key in the scope's map file, and the translation in every locale. Diagnostics (**weak warning**):
- `'<perm>' is not in <map path> — run \`php artisan permissions:build --preserve\`` (only if the map file exists)
- `No <translation key> translation in: <locales>`

### 6.11 Mail template code / placeholder

- Code: target = the template's key in `config/mail-templates.php`; completion: template codes; **warning**
  `Unknown mail template '<value>'`.
- Placeholder: target = the key in that template's `placeholders`; completion: placeholder names; **warning**
  `Mail '<code>' declares no placeholder '<value>'`.

### 6.12 Registry field

Target: the key in the entry's `fields`; completion: those keys; **warning** `No field '<value>' in this entry's fields`.

### 6.13 Repeater check

Accompanies the field path of Y4. **Warning** `'<value>' has no array-item in ModelSchema.yaml, so it cannot be a
repeater` when the field exists (§4.3) but its ModelSchema entry is missing or has no `array-item`. No warning when the
field is unknown altogether (the field path already says so).

---

## 7. Navigation markers

Shown as gutter icons (or code lenses). Each opens a list of targets; markers with no targets are not shown.

| Where | Label | Targets |
|---|---|---|
| Name of a CRUD controller class | `Domain of <Model>` | the model, its schema files, its controllers, their pages and route lines — excluding the class itself |
| Name of a model class that has schema files | `Domain of <Model>` | same |
| First top-level key of a schema YAML | `Domain of <Model>` | same, excluding the YAML itself |
| A slot-filling attribute (`#x` / `v-slot:x`, §4.8) in any `.vue` | `Schema uses of slot '<x>'` | the `slot:`, `emptySlot:` and `cells:` values equal to `x` in the schema files of every domain whose pages reach this file |

---

## 8. Find references and rename

### 8.1 Declaration strings

These PHP strings are definitions in their own right. Each carries a reference to **itself** so that "find references"
and "rename" can be started from it; that self-reference is never reported as a usage.

| Kind | Where | Renamable |
|---|---|---|
| Lang key | an array **key** in a lang file (`(resources/)?lang/<locale>/<group>.php`) | yes, in every locale at once |
| Permission | an array key in `database/permissions.php` or `database/permissions/*.php` | no (the map key `a@b` and the attribute's `'b'` differ) |
| Mail placeholder | an array key inside a `placeholders` array in `config/mail-templates.php` | yes |
| Migration column | the first argument of a `$blueprint->method('…')` call in a migration | yes |

### 8.2 What find-references covers

For each element below, search the project for the given **words** (identifier-like tokens) in YAML/JS/TS/Vue files
(any context) and in PHP files (string contents only). At each occurrence, compute the host string's references and
keep those whose targets include the element (unwrapping any presentation wrapper; a slot target matches its
attribute).

| Element | Words searched |
|---|---|
| A method of a class | its name; for `getFooAttribute`, also `foo` snake-cased; also the snake_case of the name |
| A key in a `*Schema.yaml` | the key |
| A declaration string | its contents after the last `.` and after the last `@` |
| A slot attribute in a `.vue` | the slot name after its last `.` |

### 8.3 Rename

- **Methods and schema YAML keys** use the editor's native rename; every reference found by §8.2 replaces its own
  range (one segment) with the new name.
  - **Accessor rule:** when a field-path segment resolves to an accessor method and its text is that method's derived
    snake_case name, the new text is derived the same way (`getDisplayNameAttribute` → `display_name`).
- **Declaration strings** (renamable ones) use a custom rename:
  1. Available when the cursor is on a renamable declaration, or on the **last** segment of a lang-key reference.
  2. The set renamed together: for a lang key, the same key in every locale; otherwise the string alone.
  3. Ask for the new name (default: the current one).
  4. Collect all usages of every member **before** editing; de-duplicate by (string, range); apply from the end of each
     file backwards; then replace each declaration's contents. One undoable command.

## 9. Multi-target presentation

When one reference has several targets the editor shows a chooser; rows must be distinguishable:

- **Lang targets:** `<locale>: <text>` (max 80 chars) or `<locale>: <group file name>`, location = path relative to
  the root.
- **Slot targets:** `#<slot>`, location = the `.vue` path relative to the root.
- Other targets use the editor's default presentation of the element (class name, YAML key, file name).

## 10. Quick fix: add a missing translation

Offered on both lang-key diagnostics (§6.1). Title: `Add '<key>' to <missing locales>`.

For each missing locale that already has the group file:
1. Walk the existing nested arrays as far as the key's prefix exists.
2. Build the remainder as nested inline arrays: `'a' => ['b' => 'value']`.
3. Value: the text another locale already has for the key; otherwise the last segment with `_` → space and the first
   letter capitalised (`brand_new` → `Brand new`).
4. Insert it after the target array's last entry, on its own line, indented like that entry, with a trailing comma
   (adding the missing comma after the previous last entry if needed). For an empty array, replace its interior with
   `\n<indent>\t<entry>,\n<indent>`.
5. Strings are single-quoted with `\` and `'` escaped.

Locales without the group file are skipped.

## 11. Mapping to VS Code (non-normative)

| Spec concept | VS Code API |
|---|---|
| Reference targets / go to definition | `DefinitionProvider` returning `LocationLink[]` with `originSelectionRange` = the segment |
| Completion | `CompletionItemProvider`, trigger characters `.` and `:`; `CompletionItem.command = editor.action.triggerSuggest` for "append `.` and re-open" |
| Diagnostics | a `DiagnosticCollection`, refreshed on open/change/save; weak warning → `DiagnosticSeverity.Information` or `Hint` |
| Quick fix | `CodeActionProvider` (`CodeActionKind.QuickFix`) with a `WorkspaceEdit` |
| Find references | `ReferenceProvider` (from declarations and, via the PHP extension's symbols, from methods) |
| Rename | `RenameProvider` with `prepareRename`; native PHP method rename belongs to the PHP language server, so offer a command or code action "Rename relation and schema references" for methods |
| Gutter navigation | `CodeLensProvider` (or a gutter decoration plus a command that opens a Quick Pick) |
| Icon previews | `TextEditorDecorationType` with `gutterIconPath` (SVG rendered from the glyph) and completion item `documentation` with the SVG |
| Chooser rows | `LocationLink` targets show file path and line; put the locale/slot label in a `Peek`-style Quick Pick if needed |

Parsing without the JetBrains PSI: PHP via `php-parser` (glayzzle) or `tree-sitter-php`; YAML via the `yaml` package
(with ranges); Vue via `@vue/compiler-sfc` and `@vue/compiler-dom`; JS/TS via `typescript` or `tree-sitter`. The PHP
class index (§4.3, §4.9) needs `use` resolution and inheritance: either a small own index over `app/` or the data from
an installed PHP language server (Intelephense / phpactor) through `vscode.executeDefinitionProvider` and
`executeWorkspaceSymbolProvider`.

## 12. Performance and caching

- Every index (§4.4–4.8, 4.12, models per directory) is computed once and invalidated when any relevant file changes.
  The PhpStorm build invalidates on *any* PSI change; a file-watcher keyed per index is the better target.
- Resolve calls (§5.2 P5) must be guarded by a cheap name check first.
- Find references searches by word, and only computes references for host strings that contain the word.

## 13. Known limits (v1)

- Vendor lang namespaces (`pkg::key`) and lang subdirectories are not supported.
- Columns come from migrations only, never the database; a dropped column is still offered.
- A variable in the first argument of `itti_resource_route(...)` is not expanded; `active: 'x.*'` does not consult
  route patterns.
- Route files loaded from service providers get no prefix (none of the current projects need one).
- A permission declared as a class name (attribute without a string argument) is not linked.
- `r.exportList` is always listed for a resource, whether or not the controller declares `exportList()`.

## 14. Acceptance examples

Each line is a scenario the implementation must satisfy (all are covered by the PhpStorm test suite).

1. `titleKey: admin.common.created_by` → `admin` opens `admin.php` in en and bg; `created_by` offers `en: Created by`
   and `bg: Създаден от`.
2. `columns: [rel:creator.name]` → `creator` opens `User::creator()`; `name` opens the `name` column / schema field.
3. `rel:` through a trait whose relation target is `config('userstamps.model')` resolves to `User`.
4. `- slot: locale_switcher` resolves into `Components/EditForm/LocalizedEditForm.vue`, imported by the domain's page;
   `#activator` inside a nested menu is not offered.
5. `- slot: two_factor` in `Central/AdminUsers/EditSchema.yaml` resolves only into `Pages/Central/AdminUsers/Edit.vue`,
   not the identically named tenant page.
6. `'route' => 'central.admin_users.logins.edit'` resolves when `routes/central.php` is mounted with
   `->name('central.')->group(base_path('routes/central.php'))`.
7. `"compliance_requirements.$subject.edit"` inside
   `foreach (array_keys(config('compliance-requirements.subjects', [])) as $subject)` yields one route per subject.
8. `#[Restricted('admin', scope: 'central')]` resolves into `database/permissions/central.php` and
   `admin.permissions.central.admin`.
9. A tenant model's column declared in `database/migrations/tenant/…` is a known field.
10. Find references on `User::creator()` lists `rel:creator.name` in `ListSchema.yaml`; renaming it to `author` rewrites
    `rel:author.name`.
11. Renaming `getFullNameAttribute` to `getDisplayNameAttribute` rewrites `full_name` to `display_name`.
12. Renaming `'created_by'` in `resources/lang/en/admin.php` to `made_by` renames it in en and bg, in YAML `titleKey`s
    and in `t('…')` calls.
13. `titleKey: admin.common.brand_new` offers `Add 'admin.common.brand_new' to en, bg`, which appends
    `'brand_new' => 'Brand new',` after the last entry of `common`.
14. `- repeater: notes` where `notes` has no `array-item` warns; `- repeater: lines` with `array-item` does not.
15. `- list: users` offers the route line, `UsersCtrl` and the three schema files.
