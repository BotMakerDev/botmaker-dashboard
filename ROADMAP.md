# ROADMAP

A running history of features and refactors for `botmaker-dashboard`, for future Claude Code sessions.
**Append here whenever you add a feature or refactor.**

Format: newest first. Each dated entry has a **Done** list and, when relevant, **Deferred / next**
(intentionally left for later, with enough context to pick up cold).

---

## 2026-09-05 — the Releases tab: what the last releases did, and what became of them

**Done**

- `umbrella/ReleaseLog`: parses one `releases/*.md` — the `# Release <stamp>` heading, the six-column table,
  and the `## Errors` blocks kept whole. A table line of any other width is skipped rather than shown with
  its columns shifted by one.
- `Health` classifies the script's own words for colour and never rewrites them. Two calls worth knowing:
  **`n/a (not a Maven artifact)` is dim, not green** — a module never asked the JitPack question has not
  passed it — and **`no run on <tag>` is broken, not pending**, because a tag that fired no workflow at all
  is the exact failure this log was added to catch.
- **Re-poll is `./release.sh --status <file>`.** A JitPack HEAD from here would be four lines and would
  answer a different question than the release asked; see `CLAUDE.md`. The file is rewritten in place, so a
  re-poll is a reviewable diff, and committing it stays the operator's call.
- `umbrella/Links` + `ui/Browse` (extracted from `AccountBar`, which had the only copy): the GitHub Release,
  the JitPack build and the Actions runs for a tag, on a row's context menu.

**Deferred / next**

- The log list does not mark which releases are broken without opening them. Cheap to add (the files are
  small) and deliberately not done yet — it means parsing every log on every reload.
- Phase 5, Release **preview only**. Phase 6, Queue.

---

## 2026-09-05 — the Modules tab: what is out of date, before a release

**Done**

- `umbrella/`, a package with no JavaFX in it: `Proc` (one command, output captured, a timeout that returns
  rather than throws), `Umbrella` (the module list, from `.gitmodules`), `ReleasePlan`, `DepsEnv`,
  `Changelog`, `ModuleRow`, `ModuleScan`. Every rule is a pure function over text, which is why 14 new tests
  run with no display.
- `ui/ModulesTab`: the rows in a table, a refresh that runs off the FX thread, and the script's whole output
  on the status line's tooltip when a gate refused the dry run.
- **`ReleasePlan` parses and never decides.** It reads only the block after `Deciding what to release:`,
  because the lines *before* it are the plan the flags asked for (keyed by short names — `studio-api`, not
  `botmaker-studio-api`) and the lines *after* it are the gates, which print module-prefixed lines of their
  own. Reading either would invent verdicts. A non-zero exit is ordinary: the gates run after the decide
  pass, so the verdicts still stand and are still shown.
- **Two lists this module refuses to keep**: what modules exist (`.gitmodules` says) and which are
  releasable (whether the decide pass names them says). `ModuleRow.planLabel` reports the second as *not
  released by release.sh* rather than as a category invented here.
- `DepsEnv` adds the one question the file cannot ask about itself — is this pin still the upstream's newest
  tag — and an unrecognised key still shows, since a key nobody has classified is a *new* upstream.

**Deferred / next**

- Phase 4, Releases: parse `releases/*.md`; re-poll runs `./release.sh --status <file>`.
- Phase 5, Release **preview only**.
- Phase 6, Queue: registry and gallery pull requests, gated on `Admin.canWrite`.
- The Modules tab shells to `release.sh` on every refresh, which costs a Maven run (`check_api_pointers`).
  Acceptable for a button the operator presses; if it becomes annoying, the answer is Part C's library, not
  a cache — a cached decision is a decision this app owns.

---

## 2026-09-05 — the module exists, and it visualises nothing yet

**Done**

- Repository, submodule, and the **last** reactor module — it depends on `botmaker-shared` alone, and
  nothing depends on it.
- `DashboardApp`: the window, the top bar, four empty tabs each saying what it will hold, and one
  stylesheet of its own (`css/dashboard.css`).
- `DashboardConfig`: the umbrella checkout, remembered under `CacheDirs` in `dashboard.json` — deliberately
  *not* in `credentials.json`, so a corrupt preference can never cost a sign-in.
- `UmbrellaBar`: a `DirectoryChooser` that **refuses** a directory holding neither `release.sh` nor
  `.gitmodules`. Being wrong about the checkout would otherwise present as four empty tabs.
- `AccountBar`: the OAuth device flow, through `botmaker-shared`'s `GitHubAuth`. The control is written
  here; the flow, the poll and the `0600` token file are shared's.
- `Admin`: `permissions.push` on the plugin registry. `AdminTest` holds the three failure shapes, of which
  the anonymous read — a 200 with no `permissions` block at all — is the one that must never read as push.

**Why the tabs are empty rather than absent**

The shape of the window is a decision, and a reviewer should see it before any of it works. Each phase of
the plan replaces one placeholder.

**Deferred / next**

- Phase 3, the Modules tab: latest tag, HEAD drift, whether that drift is release-relevant — which is
  **`release.sh --all --dry-run`'s** answer, not a reimplementation of `is_release_irrelevant`.
- Phase 4, Releases: parse `releases/*.md`; re-poll runs `./release.sh --status <file>`.
- Phase 5, Release **preview only** — no tag is pushed from this window until Part C's library owns the
  decision in typed code.
- Phase 6, Queue: registry and gallery pull requests, the entry rendered as fields, the gate's check-run
  verdict, and approve/merge gated on `Admin.canWrite`.
