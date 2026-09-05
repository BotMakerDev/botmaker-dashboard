# ROADMAP

A running history of features and refactors for `botmaker-dashboard`, for future Claude Code sessions.
**Append here whenever you add a feature or refactor.**

Format: newest first. Each dated entry has a **Done** list and, when relevant, **Deferred / next**
(intentionally left for later, with enough context to pick up cold).

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
