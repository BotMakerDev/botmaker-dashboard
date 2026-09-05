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

- **The app shell.** One window: the umbrella-checkout picker (remembered under `CacheDirs`, refused unless
  the directory holds both `release.sh` and `.gitmodules`), GitHub sign-in through the shared OAuth device
  flow, the admin badge, and the four tabs — Modules, Releases, Release, Queue — each stating what it will
  hold. Nothing reads a release or a pull request yet.
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
- **`Admin`** — `permissions.push` on `botmaker-plugin-registry`, read from the GitHub API for the
  signed-in account. Every failure (offline, not signed in, a token whose scope was narrowed, a repository
  the account cannot see) is read-only with a reason rather than an error.
