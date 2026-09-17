# Changelog

What each version of `botmaker-dashboard` changes, in a few bullets. `ROADMAP.md` is the detailed
engineering log; this is the short answer.

**This module is not released.** It has no tag, no JitPack build and no GitHub Release, so nothing here is
published as release notes and `release.sh` does not gate it. The file exists because a module without one
is a module whose history lives only in commit messages — and because that changes the day the dashboard
ships to anyone but its author.

Sections are `## [x.y.z] — YYYY-MM-DD`, newest first.

## [0.0.1] — 2026-09-17

### Added

- **Installable, on Linux.** A `v*` tag now builds an rpm and a deb (jpackage, through the `dist`
  profile) and publishes them as a GitHub Release with this section as its body. The umbrella release cuts
  the tag (`--dashboard`, last in the chain), and `.deps.env` records the shared, cli, contract and loader
  refs the package was built from. `--cli` forces `--dashboard`: the Release tab calls the cli's release
  library in-process, so an installed dashboard decides by the cli it was built with.
- **A stale-cli notice in the top bar.** An installed build compares the cli it was packaged with (baked
  into the jar) against the checkout's `botmaker-cli`, and says *built with cli vX, checkout at vY — preview
  may follow older rules* when they differ. A development run shows nothing.
- **CI builds the cli from source.** The `build` job had installed `botmaker-shared` only since the
  `botmaker-cli` dependency arrived on 2026-09-05, and had been red since; it now installs the contract,
  the loader, shared and the cli.

- **Draft all… on the Changelog tab.** One press writes and commits an `[Unreleased]` section in every
  module that has none. A module with no commits since its newest tag — one being re-released only because
  an upstream moved — gets one line saying so and its previous section carried forward, with no model
  asked; the rest are drafted with Claude. It asks first, names the modules, and reports how many were
  drafted, copied and left. Offered to the maintainer even when Claude is not on this machine, because the
  copies still happen.
- **A preview on the Release tab writes the changelogs it needs.** Before the plan is computed, every
  module the flags would cut and whose changelog would make the gate refuse is drafted or copied forward
  and committed, and the output says which. A module that could not be — Claude absent, every account
  refusing, a dirty `CHANGELOG.md` — is a refusal of the preview, by name, rather than the gate's generic
  one three minutes later. Execute stays dead either way.
- **Gallery tiers in the Catalog tab.** A Tier column shows each bot as Vetted (with the release) or
  Community; double-click it to open the bot's repository. **Vet…** proposes a `vetted/` record pinning a
  release (the newest, by default), and **Revoke vetting…** proposes deleting it. Both open a pull request
  that the gallery's checks run over, and you merge it from the Queue tab.
- **The Queue tab shows what the gallery's merge job decided.** An Auto-merge column reads `waiting (rate
  limit)`, `needs maintainer` or `merges when checks pass`, and the selected row shows the job's own
  comment. A `vetted/` pull request now counts as a well-shaped one-file submission.

### Fixed

- **Opening a link no longer freezes and then kills the window.** Links opened through `java.awt.Desktop` on
  the JavaFX thread, and on Linux that hangs the application. It ended a release being cut from the Release
  tab after four tags. Links now open through the platform opener on a background thread. If nothing opens,
  the tab's status line shows the URL.
- **Dialogs are readable.** Every dialog, context menu, tooltip and combo list now gets the stylesheet, not
  Modena's black on white. The Release tab's output no longer renders dark text on a dark background, and
  verdict colours in tables now apply.

- **Picking a level on a row now asks for that row.** It used to change nothing unless the row was already
  ticked, while Execute stayed armed for the `--all` level. Choosing a level or typing a version ticks the
  row, which changes the command line and so disarms Execute.

### Added

- **Execute starts the release as a process of its own**, which outlives the window. The Release tab draws it
  from the files that process leaves: a tile each for tagged, JitPack, Actions and elapsed time; a timeline
  of where the minutes went; and a lane per module with a `Commit — Tag — JitPack — Actions` stepper that
  opens to the error text when a step fails. Close the window mid-release and reopen it: the tab reattaches.
  The tab's header shows `● Releasing — 4/10` from any other tab.
- **The Releases tab lists every release from its tags**, so a release that wrote no log — the one of
  2026-09-16 — is there. A log, where one exists, names the release and fills in what it recorded. Each
  release is drawn with the same tiles and lanes as a running one. JitPack and Actions are polled and cached,
  with each answer's age; *Deep check* runs the release's own clean-room resolve, and a quick check says
  `published (pom HEAD)` rather than `ok`.
- **A Changelog tab.** The `## [Unreleased]` section of any module, in an editor, with the commits since its
  newest tag and its last released section beside it. Save rewrites that one section — every other byte of
  the file survives — and commits `CHANGELOG.md` inside the submodule, without pushing. It refuses a file
  somebody was already editing. This is what a release refused for *no section to stamp* now sends you to,
  instead of out of the window.
- **Draft with Claude**, for the maintainer only: it asks the least-used `cswap` account, rotating on a
  refusal, and fills the editor. Nothing is saved until Save. The status line names the account slot and
  never the address.
- **Links everywhere.** Every module row carries GitHub · Actions · JitPack · Releases, with
  *Changes since \<tag\>* on its right-click menu when HEAD has moved past the tag; every catalog entry
  carries the same four for the repository it names, plus its entry file. Beside them, **what CI says about
  `main`** — the release's own `CiGate` verdict, in its words, with the whole refusal on hover and a click
  that opens the runs.
- **Every module has a row before any preview**, in tag order, with its latest tag and what a level would
  cut. The previous table stayed empty until the first preview.
- **A refusal is a red banner above the output**, one line per gate in its own words, instead of a count in
  the status line.
- **A light theme**, and a ☀/☾ toggle in the top bar. The choice is remembered. With no choice, the window
  follows the desktop's colour scheme.

- **The Release tab can cut the release.** It could only describe one before: `ReleaseSpec` appended
  `--dry-run` to every command line with no way to leave it off, and what the tab handed back was the line
  to paste into a terminal. Preview and Execute are now one call — `Release.run`, with a `Runner` as the
  only difference — which is the property a shelling window could not have: the plan on screen was produced
  by the code that does the work, so it cannot drift from it. The output is streamed as it is produced,
  because a window that looked frozen during a tag chain is one somebody force-quits halfway through it.

  **Three guards, in the order they apply.** Execute is dead until a preview has run *in this session, with
  these exact flags, against this checkout* and returned no refusal — armed by comparing `ReleaseSpec`
  values, not by setting a boolean, so a tick or a keystroke takes the button dead again and putting the
  flag back puts it back. Then a confirmation that lists every module and version about to be tagged and
  will not enable its own button until `release` is typed. And a release arms nothing: whatever it leaves
  behind, the way to get the button back is to preview again against the checkout as it now is.

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

- **Edit and Unpublish, as pull requests.** Both branch `main`, write or delete the one entry file on that
  branch, and open a pull request; neither touches `main` and neither publishes or unpublishes anything by
  itself. Gated on `permissions.push`, like the Queue tab's writes and for the same reason — a courtesy so
  the operator learns before typing, never a boundary, since GitHub answers 403 regardless.

  A pull request rather than a push because `index.json` is generated from the entry files by CI, so a
  commit straight to `main` leaves an index that disagrees with the entries until the next job runs — and
  because a pull request runs `RegistryGate` over the *result*, which is the only way an edit gets the same
  check a submission gets. No fork: an operator with push rights pushes the branch directly, which is why
  this is not a reuse of `PluginPublishCommand`'s flow.

  The blob `sha` from the listing goes back with the write, so GitHub refuses it if the file moved since it
  was read. The branch carries a UTC timestamp, so a second edit while the first pull request is still open
  is not a 422 naming an existing ref.

  Two things the dialogs do that are deliberately **not** gates: the editor says whether the text parses and
  does not refuse it, and an edit that changed nothing opens no pull request. Unpublish asks for the id to be
  **typed**, because merging it removes the entry for everyone and the filename is the claim. The editor is
  a text area over the JSON rather than a form — a form shows only the keys it knows, which is the same
  reason `EntryFields` reads the file and not a schema.

- **The app shell.** One window: the umbrella-checkout picker (remembered under `CacheDirs`, refused unless
  the directory holds both `release.sh` and `.gitmodules`), GitHub sign-in through the shared OAuth device
  flow, the admin badge, and the tabs — Modules, Releases, Release, Queue, Catalog — each stating what it
  will hold. Nothing reads a release or a pull request yet.
- **The Modules tab.** One row per submodule in the checkout: its newest tag and how far HEAD has moved
  past it, whether the working tree is dirty, **what the decide pass decided about that movement — in its
  own words, never re-derived here** — whether `CHANGELOG.md` has an
  `## [Unreleased]` section for a release to stamp, and each `.deps.env` pin with the upstream's newest tag
  beside it when the two differ. A module the pass never names is reported as one it does not release,
  which is how the gallery, the plugin registry and this repository are told apart from the eleven it does.
- **The Releases tab.** The committed `releases/*.md` logs, newest first: the table as the release wrote it,
  the full error text under it, and each verdict coloured by what it means without a word of it being
  rewritten. **Re-poll calls the release's own `--status`** rather than asking JitPack and Actions directly,
  so "ok" keeps meaning what a clean-room resolve decided it means; the log is rewritten in place, as a
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
- **The Release tab's preview.** Module checkboxes with a version or bump level each, `--all <level>`,
  `--force` and `--no-wait-jitpack`, rendering the run's whole output: the decided version per module, why
  each was skipped or forced, the tag order and the gate verdicts, none of it re-rendered here. The module
  rows are the decide pass's own list, which is why they appear after the first preview rather than before
  it.
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

- **The decide pass is called, not shelled to and parsed.** `umbrella/ReleasePlan` read module verdicts out
  of `./release.sh --all --dry-run`'s stdout with a regular expression; it is deleted. `ModuleScan` calls
  `Plan.decide` and the Release tab calls `Release.run`, both through `com.botmaker.cli.release` — the same
  library `botmaker release` and the release workflow call.

  **The rule did not change; it got stricter.** *Never reimplement a decision the release owns* was kept
  before by putting the decisions out of reach, behind a pipe, at the cost of a parser that could mis-read a
  line the script reworded and a subprocess per scan. It is kept now by there being one implementation that
  every caller reaches. The typed `Plan.Decision` replaces a `Verdict` parsed from text, so a module's
  version is a `Version` rather than a captured group.

  One thing the Modules tab now says less: it no longer reports gate verdicts. The gates belong to a release
  rather than to a scan and they cost Maven; the Release tab runs them on demand.

- **`ReleaseSpec` spells `botmaker release …`, not `./release.sh …`.** The line an operator copies when the
  window cannot finish has to reach the same code the button does — two implementations is exactly what the
  fallback used to be. `--execute` is the caller's argument rather than a field, so the preview line and the
  release line are spelled by one method and differ by that word.

- **Re-poll calls `ReleaseStatus.repoll` instead of shelling to `./release.sh --status <file>`.** It is the
  last subprocess this window ran that was not `git`, and it had to go the day the script became a wrapper:
  shelling would have built a jar to run the code already on this classpath. What it asks is unchanged —
  JitPack through the release's own clean-room resolve, Actions through the release's own poll — because
  that was never about *how* the answer was fetched, only about whose answer it is. The lines stream into
  the status label as each module is polled, and a failure is a value, not an exception.

- **The contents API has one caller instead of two.** `Queue` read an entry file at a pull request's head
  and decoded the base64 itself; `Catalog` needs the same bytes on `main`, one ref apart. Both now go
  through `Contents`, which also owns the "reads work signed out, writes do not" token rule that had been
  written twice.
