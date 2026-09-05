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
- **`Admin`** — `permissions.push` on `botmaker-plugin-registry`, read from the GitHub API for the
  signed-in account. Every failure (offline, not signed in, a token whose scope was narrowed, a repository
  the account cannot see) is read-only with a reason rather than an error.
