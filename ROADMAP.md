# ROADMAP

Completed work up to 2026-10-05: see CHANGELOG.md, docs/refactor/, and `git show aea60a0:ROADMAP.md`.

## Open

- **Releases list marks broken releases without opening them.** Cheap (the logs are small); it means parsing
  every log on every reload.
- **A Doctor tab**: git identity, the release token's scope across every repository, `mvn`, JitPack
  reachability — the environment `--ci` refuses on, answered before a release.
- **Drift alerts on the Modules tab**: one *what is owed* line rolling up stale pins, missing `[Unreleased]`
  sections and tags whose JitPack or Actions verdict was never green.
- **A per-module diff viewer**: commits and files since a module's last tag, release-irrelevant ones greyed
  out.
- **`--all <level>` has no arrow** beside its `ComboBox`; the honest place is the rows, once *explicit beats
  `--all`* is shown there.
- **Gate verdicts left the Modules tab** (they cost Maven). If missed, bring them back as a button, not in the
  scan.
- **Catalog write path is untested end to end.** The four HTTP calls run only against a live data repository;
  a fake needs an interface over `botmaker-shared`'s concrete `GitHubClient`.
- **Catalog via the Git Trees API plus raw files at a sha** — revisit if a signed-out first load of a larger
  catalog hits the rate limit.
- **`Admin` probes push on the plugin registry only**; vetting needs push on the gallery. Add a second probe
  the day the two repositories have different maintainers.
- **The Actions node reads `pending` for the whole chain** (Actions is polled after the last tag). A live poll
  per tag is not planned.

## Deliberately not planned

- No auto-refresh or webhook on the Queue tab; if the queue stops being small, use the GraphQL API, not a
  cache.
- No `.icns` or Windows `.ico` wiring: this module ships Linux packages only (`icon.ico` is generated anyway).
