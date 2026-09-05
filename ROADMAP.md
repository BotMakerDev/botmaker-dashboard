# ROADMAP

A running history of features and refactors for `botmaker-dashboard`, for future Claude Code sessions.
**Append here whenever you add a feature or refactor.**

Format: newest first. Each dated entry has a **Done** list and, when relevant, **Deferred / next**
(intentionally left for later, with enough context to pick up cold).

---

## 2026-09-05 — the Queue tab: the submissions, and somebody else's verdict on each

**Done**

- `github/Queue`: both data repositories' open pull requests, their files and their check runs, plus the
  four writes (approve, request changes, squash-merge, and reading the entry at the PR's own head commit).
  Reads work signed out — both repos are public — and the token is passed when there is one.
- `github/Submission`: **the only judgement this app makes is shape.** One entry file and nothing else;
  `index.json` by hand is the common stray, and two entry files is *no* entry file, because which id is
  being claimed has no answer. Merge is refused locally on a wrong shape; everything else GitHub decides.
- `github/Checks`: the CI's conclusion reduced to one verdict, with a failure beating a run still going —
  nothing the second concludes can make the first pass. **`NONE` is a state of its own**, unlike
  `ReleaseLog.Health`'s reading of `no run on <tag>` as broken: a tag is finished, a pull request is not.
- `github/EntryFields`: the entry flattened into rows, **from the file rather than a schema** — a key this
  window has never heard of is the one a reviewer most needs to see. Broken JSON renders as itself.
- `ui/QueueTab`, and the last placeholder is gone: all four tabs have content, so `DashboardApp.placeholder`
  is deleted.

**Deferred / next**

- The queue is 2n+2 requests per refresh (files and checks per submission). Right while n is small, and n is
  small by construction — this is a queue somebody empties, not a feed. If it stops being small, the answer
  is the GraphQL API, not a cache.
- No auto-refresh and no webhook. A window that polls github.com in the background is a service, and this is
  not one.
- Part C. The execute button arrives with slice 5.

---

## 2026-09-05 — the Release tab: what a release would do, and the line to type

**Done**

- `umbrella/ReleaseSpec`: the flags as a record, `command()`/`commandLine()`, and `preview(umbrella)`.
  **`--dry-run` is appended there and nowhere else can leave it off** — Part B visualises and does not
  execute, and enforcing it in the only class that builds a command means a second caller cannot forget.
- `ui/ReleaseTab`: the flags on the left, the script's own output whole on the right, and the command line
  between them as the tab's real deliverable. No execute button.
- **The module rows are `release.sh`'s list, not one kept here** — they come from the decide pass's verdicts,
  so they appear after the first preview. A module the pass stops naming is dropped rather than held with a
  stale verdict.
- `ReleaseSpec.flagFor` derives `--plugin-toolkit` from `botmaker-plugin-toolkit`; no flag table.
- A typed version is marked, not refused mid-keystroke, and Preview is disabled while one is marked — so the
  script is never asked a question it will only answer with `bad version/level`.

**Deferred / next**

- Phase 6, Queue. The execute button arrives with Part C slice 5, not before.
- The tab runs the whole decide pass to learn the module list. Part C's library removes the cost; a cache
  here would be a decision this app owns.

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
