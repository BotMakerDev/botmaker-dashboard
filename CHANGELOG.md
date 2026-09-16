# Changelog

What each version of `botmaker-dashboard` changes, in a few bullets. `ROADMAP.md` is the detailed
engineering log; this is the short answer.

**This module is not released.** It has no tag, no JitPack build and no GitHub Release, so nothing here is
published as release notes and `release.sh` does not gate it. The file exists because a module without one
is a module whose history lives only in commit messages — and because that changes the day the dashboard
ships to anyone but its author.

Sections are `## [x.y.z] — YYYY-MM-DD`, newest first.

## [Unreleased]

### Added

- **The Catalog tab — what is published**, which the Queue tab cannot answer. Queue lists open pull
  requests, and that is zero most days; this lists every **merged** entry on both data repositories:
  `plugins/<plugin-id>.json` from the registry and `bots/<owner>-<repo>.json` from the gallery, with the
  entries carrying the reserved `template` tag shown as templates rather than bots. One row per entry with
  its identity, name, tags and description; the whole file as fields below, through the same `EntryFields`
  the Queue tab uses; and a status line counting plugins, bots, templates and — separately — entries that
  could not be read.

  Three things about it are deliberate. **It reads github.com, not the checked-out submodule**: a merged
  entry exists on `main`, and the umbrella's recorded pointer trails it every time either repository's CI
  commits a regenerated index, so a stale catalog would be indistinguishable from a current one. **It reads
  the entry files and not the generated `index.json`**, because the index is what a job last produced and
  the files are what the repository holds. And **an entry that will not parse is a row, not a dropped one**
  — it is on `main`, so somebody merged it, and it is exactly the entry an operator has to see.

  The typed halves are `botmaker-cli`'s own records (`RegistryEntry`, `GalleryEntry`) read through
  `Registry.mapper()`, which ignores unknown keys — so a field added to the entry shape tomorrow lists
  today, and still shows up in the field view beside it.

- **The app shell.** One window: the umbrella-checkout picker (remembered under `CacheDirs`, refused unless
  the directory holds both `release.sh` and `.gitmodules`), GitHub sign-in through the shared OAuth device
  flow, the admin badge, and the tabs — Modules, Releases, Release, Queue, Catalog — each stating what it
  will hold. Nothing reads a release or a pull request yet.
- **The Modules tab.** One row per submodule in the checkout: its newest tag and how far HEAD has moved
  past it, whether the working tree is dirty, **what `./release.sh --all --dry-run` decided about that
  movement — quoted in the script's own words, never re-derived here** — whether `CHANGELOG.md` has an
  `## [Unreleased]` section for a release to stamp, and each `.deps.env` pin with the upstream's newest tag
  beside it when the two differ. A module the script never names is reported as one it does not release,
  which is how the gallery, the plugin registry and this repository are told apart from the ten it does.
- **The Releases tab.** The committed `releases/*.md` logs, newest first: the table as the release wrote it,
  the full error text under it, and each verdict coloured by what it means without a word of it being
  rewritten. **Re-poll runs `./release.sh --status <file>`** rather than asking JitPack and Actions directly,
  so "ok" keeps meaning what `resolve_clean_room` decided it means; the log is rewritten in place, as a
  reviewable diff the operator commits. Right-click a row to open its GitHub Release, its JitPack build or
  the workflow runs for its tag.
- **A level picker instead of a text box, and it says what the level would cut.** Each module row carries
  `patch | minor | major | x.y.z` as one segmented control, with the exact-version field enabled only by the
  fourth. Beside it, **Would cut** reads `1.1.6 → 1.2.0` — resolved by `com.botmaker.cli.release`, the port
  of `release.sh`'s own `latest_version` and `resolve_version`, so the number shown is the number the release
  will compute rather than a plausible-looking guess. That is why this module now depends on `botmaker-cli`'s
  main artifact, with the contract and plugin-host excluded: it calls a library, it does not become a host.
  A module the release never cuts (`botmaker-gallery`, this repository) says *not released* rather than
  inventing an arrow.
- **The Release tab — a preview, and no way to execute.** Module checkboxes with a version or bump level
  each, `--all <level>`, `--force` and `--no-wait-jitpack`, rendering `./release.sh --dry-run`'s whole output:
  the decided version per module, why each was skipped or forced, the tag order and the gate verdicts, none
  of it re-rendered here. **`--dry-run` is appended by the code that builds the command and cannot be left
  off** — the terminal stays the only place a tag is pushed — so what the tab hands back is the exact command
  line to paste. The module rows are the decide pass's own list, which is why they appear after the first
  preview rather than before it.
- **The Queue tab.** Every open pull request on `botmaker-plugin-registry` and `botmaker-gallery`: who
  opened it, the one entry file it adds rendered as fields read out of the file itself, and **the gate's own
  check-run conclusion** — `RegistryGate`, run by the registry's CI, never a validation repeated here. A
  submission touching anything besides its own entry is flagged and cannot be merged from this window: one
  file per entry is what makes two same-day submissions conflict-free, and a hand-edited `index.json` is
  stale the moment the next one merges. Approve, request changes with a comment, and squash-merge, enabled
  by `permissions.push` and never by a list kept here.
- **`Admin`** — `permissions.push` on `botmaker-plugin-registry`, read from the GitHub API for the
  signed-in account. Every failure (offline, not signed in, a token whose scope was narrowed, a repository
  the account cannot see) is read-only with a reason rather than an error.

### Changed

- **The contents API has one caller instead of two.** `Queue` read an entry file at a pull request's head
  and decoded the base64 itself; `Catalog` needs the same bytes on `main`, one ref apart. Both now go
  through `Contents`, which also owns the "reads work signed out, writes do not" token rule that had been
  written twice.
