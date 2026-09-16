# ROADMAP

A running history of features and refactors for `botmaker-dashboard`, for future Claude Code sessions.
**Append here whenever you add a feature or refactor.**

Format: newest first. Each dated entry has a **Done** list and, when relevant, **Deferred / next**
(intentionally left for later, with enough context to pick up cold).

---

## 2026-09-16 — round 2, phase 6: the Changelog tab, and a drafter through cswap

**Done**
- **`umbrella/ChangelogEdit`**: `section` / `lastStamped` / `replace` are pure and spliced by offset —
  `Stamp`'s rule, so line endings, the final newline and every other section survive. A missing
  `## [Unreleased]` is inserted above the newest stamped heading; a file with no heading at all gets one at
  the end. `save` writes it and runs `git commit -- CHANGELOG.md` **inside the submodule, no push**, refusing
  a file that was already dirty when the tab read it, one that changed on disk since, and an edit that
  changes nothing. `commitsSince` / `diffStat` are the raw material for a draft.
- **`umbrella/CswapAccounts`**: `cswap list` parsed to slot + 5h/7d usage, **no email kept anywhere**;
  `byLeastUsed` orders by five-hour usage, ties by slot. A listing it does not recognise is no accounts, not
  a wrong one.
- **`umbrella/ClaudeDraft`**: `cswap run <slot> -- claude -p --model sonnet --effort medium --output-format
  text --allowedTools ""`, prompt on stdin, three-minute timeout, each account tried in turn. `claude` exits
  0 on a usage limit, so the output is checked for one — otherwise that sentence becomes the release notes.
  The prompt carries the changelog preamble, the last stamped section, the commits and the diff stat, and
  asks for the section body only.
- **`ui/ChangelogTab`** (sixth tab, beside Release): module list, the `[Unreleased]` body in an editor, the
  commits since the newest tag and the last stamped section below it. The tag name is `Tags.latest` +
  `Tags.existingRef`, the library's answer. *Draft with Claude* is visible only when both programs are on
  `PATH` **and** the signed-in login is the repository owner — hidden, not disabled — and it only fills the
  editor.
- Tests: `ChangelogEditTest` (sections, CRLF, insertion, the empty body), `ChangelogSaveTest` (a real git
  repository: one file in the commit, a dirty file refused, a no-op save), `CswapAccountsTest`,
  `ClaudeDraftTest` (argv, prompt, the usage-limit sentence). 161 green.
- Checked against the real checkout: `botmaker-studio` reads as *no [Unreleased] section* — the state the
  half-cut release left it in — `botmaker-pilot` as *no CHANGELOG.md*, and `botmaker-session` with 492
  characters of prose and `## [0.0.12] — 2026-09-04` as its last stamped section.

---

## 2026-09-16 — round 2, phase 5: links everywhere, and a CI badge

**Done**
- **`umbrella/Links` grew a second kind of destination.** It named a *tag's* three pages (Release, JitPack,
  Actions); it now also names a *repository's* four — `repo`, `actions` on `main`, `jitpackPage`, `releases`
  — plus `compare(module, from, to)`. `forModule(module[, tag, ahead])` and `forRepository(slug)` return the
  list a tab draws, and `slug(repo)` reduces a registry entry's `repo` field (a URL, a `.git` suffix, a bare
  slug) to `owner/name`, or to empty — a guessed link opens somebody else's page.
- **`ui/widgets/LinkBar`**: the buttons and the row's context menu, built from one list, so the two cannot
  offer different pages. Opening is the caller's `Consumer<String>` (`Browse.open` in the app), because the
  phase 1 crash was a widget knowing how to start a browser.
- **Modules tab**: a *Links* column of four buttons per module, and a right-click menu that adds
  *Changes since \<tag\>* when HEAD has moved past it (too wide for the column, exactly right on a menu).
- **A *CI on main* column**, filled in after the scan by `umbrella/CiStatus` — `CiGate.check(module, false)`
  rendered: green, red, or dim with the gate's own sentence, the whole refusal as the tooltip, and a click
  opening the runs on `main`. Four daemon threads, since each check is a `gh` process on the network; the git
  half of the tab never waits for it. A module the release does not cut is not asked.
- **Catalog tab**: the entry's repository, its Actions, its JitPack project, its releases and the entry file,
  as a link row under the heading and as a right-click menu on the row. The old single *Open on GitHub*
  button is gone — it is the *Entry file* link now. An entry whose repository cannot be read offers only its
  own file.
- Tests: `LinksTest` (the lists, the comparison rule, the slug reductions), `CiStatusTest` (each
  `GateVerdict` arm, and that an unreleased module is never asked), `LinkBarTest` (buttons open, a redraw
  replaces, the menu matches), `CatalogTest` (an entry's links, and an unreadable one's).

---

## 2026-09-16 — round 2, phase 4: the Releases tab from tags, with or without a log

**Done**
- **`umbrella/ReleaseHistory`**: every version tag of every `Module` (`git for-each-ref … %(creatordate:iso-strict)`,
  local first, then after `git fetch --tags`), grouped into releases. **The plan's 90-minute gap was wrong for
  this history**: 2026-09-04 23:06 and 23:18 are twelve minutes apart and tag the same seven modules, and
  00:30/00:48 are one cli release each. The rule is a 15-minute gap (a JitPack wait is ten at most) **or** a
  module already tagged in the group. Checked against the real checkout: 83 releases, all 8 committed logs
  land on a group, and 2026-09-16 is one group of 4 with no log.
- A log is laid over the group sharing the most `(module, tag)` pairs and names it; its untagged rows
  (`FAILED`, `not reached`) become lanes; a log matching no group is still listed. **Caveat**: tags are
  lightweight, so a tag's date is its commit's. A module whose release commit was skipped (the pilot, a
  resumed release) dates from an older commit and can land in an earlier group.
- **`umbrella/Verdicts`**: a `.pom` HEAD on `Jitpack.pomUrl`, spelled `published (pom HEAD)` /
  `missing (pom HEAD)` — never `ok`; `CleanRoom.resolve` as **Deep check** (`ok (resolves clean)` / `BROKEN`);
  `Actions.poll` for Actions. `ReleaseLog.Health` reads `published` as OK.
- **`umbrella/VerdictCache`**: `releases-cache.json` under `CacheDirs`, keyed `module@tag`, each half stamped.
  Settled: `success`, `FAILED —` (Actions' real words — the first draft used `failure`, which Actions never
  prints; found by polling host v0.1.0), `published`, and both clean-room answers. Anything else is stale after
  10 minutes. A refresh never downgrades a deep-check answer to a HEAD.
- **`ReleaseProgress.past`** draws a past release with Phase 3's lanes: cache, then log cell, then `pending`;
  the lane's stage line says the words behind each node and their age; elapsed is the gap since the previous
  tag. `health()` feeds the list's dot.
- **`ui/ReleasesTab`** rewritten: list of releases (date · modules · `no log`, health dot) on the left, the
  `ReleaseBoard` on the right. Opens from cache, polls stale verdicts of the selected release on one background
  thread. *Refresh verdicts* re-polls all of them and fetches tags; *Deep check*; *Write back to the log* is
  `ReleaseStatus.repoll`, offered only when a log exists. A `Backend` seam for tests.
- Tests: `ReleaseHistoryTest` (the real tag dates, the repeat-module split, gaps, log merge, a checkout with no
  modules, a past release over cache and log), `VerdictCacheTest`, `ReleasesTabTest` (tags, no `releases/`).

---

## 2026-09-16 — round 2, phase 3: Execute in its own process, and a board instead of log text

**Done**
- **`umbrella/ReleaseJob`** is a `main` that runs `ReleaseRun.go(…, execute=true, …)` and nothing else, with
  every line stamped (`<instant> <text>`) and flushed, and a last `release-job: done|refused|stopped …` line.
  That line is the only way to tell *finished* from *killed*: a refusal leaves no log, and a run that died
  after its last tag leaves a log that looks finished. `--dry-run` rehearses the machinery by hand; the
  window never passes it.
- **`umbrella/ReleaseLauncher`** starts it as `setsid <java.home>/bin/java -cp <this JVM's class path>
  ReleaseJob <umbrella> <stamp> <flags>`, output to `releases/.running/<stamp>.out`, pid beside it
  (gitignored in the umbrella). `setsid` because an IDE's stop button signals the whole process group.
  **The classpath risk the plan named did not bite**: under `javafx:run` the plugin puts every jar, JavaFX
  included, on `-classpath` (read from `/proc/<pid>/cmdline`). `jdk.module.path` is folded in anyway, for a
  launcher that does split them. Liveness is `ProcessHandle` plus a check that the pid still runs
  `ReleaseJob`, since pids are reused.
- **`ReleaseSpec.parse`**, `command()`'s inverse, and a round-trip test by value equality — the arming's own
  comparison. An argument the release does not take is refused, never dropped.
- **`umbrella/ReleaseProgress`**, pure: the log wins wherever it has an answer, and the output fills in a row
  the log still calls `pending` (which step it reached, whether it is waiting on JitPack). Lanes, the four
  tiles' counts, a phase (`DECIDING … DONE/REFUSED/STOPPED/DIED`), per-lane elapsed time closed at the next
  module's line, and the tile filters. A JitPack timeout reads as *waiting*, not failed, until the verify
  pass answers.
- **`ui/widgets/`**: `SummaryTiles`, `ModuleLane`, `ReleaseTimeline`, `LiveBadge`, and `ReleaseBoard` composing
  them. They draw a model and compute nothing. The pulse runs only while a node is running and the tab is
  selected.
- **Release tab rework.** Rows for every `Order.TAG` module at construction. Choosing a level or typing a
  version ticks the row — the reported bug, where a level on an unticked row changed nothing while Execute
  stayed armed. Refusals are a banner. Execute launches the child and the tab polls its two files once a
  second (a poll rather than a `WatchService`: two directories, one file rewritten whole, and a process
  liveness no file event reports); the output moves to a collapsed *Output* pane. Preview and Execute are
  dead while a job is alive, and a live job is reattached when the tab opens. A `Backend` seam lets a test
  hand in a preview.
- **TestFX + Monocle**, Studio's pairing (monocle 21.0.2 on JavaFX 25). `ReleaseProgressTest`,
  `ReleaseLauncherTest`, `ModuleLaneTest`, `SummaryTilesTest`, `ReleaseTabTest`. The module's "headless by
  construction" rule still holds for rules; these test drawing.

**Deferred / next** — the Actions node reads `pending` for the whole chain, because Actions is polled only
after the last tag; a live Actions poll per tag would be a second reader beside the library's and is not
planned. Nothing drives a real release through the board in a test: the maintainer's scratch-umbrella run is
the check (plan, *Verification*).

---

## 2026-09-16 — round 2, phase 1: the crash, readability, and a light theme

The first real `--all minor` cut from the Release tab stopped after four tags. The window died during an
Unpublish, and the release was running in the same JVM. Plan: `~/.claude/plans/am-i-ready-for-jolly-wirth.md`.

**Done**
- **`ui/Browse` no longer touches AWT.** It ran `Desktop.getDesktop().browse` on the FX thread; on Linux that
  initialises AWT inside a JavaFX app, which froze the window and then killed it. It now runs
  `Os.openCommand` (`xdg-open`/`open`/`rundll32`) on a background thread, falls back to
  `HostServices.showDocument` on the FX thread, and on failure hands the URL to the tab's status line.
  The platform opener comes first because `HostServices` on Linux guesses from a fixed browser list and
  fails silently, so it could never report that nothing opened.
- **Light and dark.** `dashboard.css` holds two token palettes; every rule reads tokens only. Modena's
  `-fx-base`/`-fx-background`/`-fx-control-inner-background` point at the tokens, so controls the file never
  names follow too. A ☀/☾ toggle in the top bar, remembered as `theme` in `dashboard.json`; `null` follows
  `Platform.getPreferences().getColorScheme()`. `Theme` is a closed enum with a total `fromId`.
- **`ui/Themed`.** Listens to `Window.getWindows()` and gives each new window the stylesheet and the theme
  class on its scene root. `Themed.dialog(dialog, owner)` is the call site's one line, and sets the owner
  that `ReleaseTab`'s confirmation never had.
- **Readability fixes:** `.output-text` had no text fill, so the preview rendered dark on dark; text areas,
  text fields, combo boxes, check boxes, context menus, tooltips, scroll bars and the dialog header had no
  rules at all. The `cell--*` state colours never applied inside tables, because
  `.table-view .table-cell` has two classes and `.cell--ok` has one. Each is now spelled under both.
- `DashboardConfig` keeps both keys on every save (`withUmbrella`, `withTheme`) and has path-taking
  `load`/`save` overloads for tests.

**Deferred / next** — phases 3–6 of the plan: Execute as a child process with progress widgets, the Releases
tab from tags alone, links, and the Changelog tab.

**Phase 2 (same day, in `botmaker-cli`)** gave release logs a `stage` column after `tag`. `umbrella/ReleaseLog`
reads six- and seven-cell rows and keeps the stage on `Row`; nothing draws it yet.

---

## 2026-09-16 — the window cuts the release, and stops parsing stdout to do it

**Done**

- **`umbrella/ReleaseRun`** — one release, previewed or cut, through `com.botmaker.cli.release.Release.run`.
  It builds a `Runner` and a place to put the lines and decides nothing. A preview and a release are the
  same call with a different `Runner`, which is the property a shelling window could not have: the plan on
  screen was produced by the code that will do the work. In-process, because `botmaker-cli`'s main artifact
  is already on this application's classpath and a subprocess would put the output back behind a pipe.
  Every failure is a **value** — a `ReleaseRefusal` and any other `RuntimeException` become `error()` — so
  nothing escapes into a `CompletableFuture` and reaches the operator as `Preview failed: null`.

- **`umbrella/ReleasePlan` is deleted**, with its test. It read module verdicts out of
  `./release.sh --all --dry-run`'s stdout with a regular expression. `ModuleScan` calls `Plan.decide`;
  `ModuleRow` holds an `Optional<Plan.Decision>`; `ReleaseTab` reads `Plan.decisions()`. The rule *never
  reimplement a decision the release owns* did not change — it got stricter. It was kept before by putting
  the decisions **out of reach**; it is kept now by there being one implementation that every caller
  reaches.

- **`umbrella/ReleaseSpec` rewritten.** `Map<Module, String>` rather than `Map<String, String>`;
  `requested()` hands `Plan.decide` its request through the library's own `Requested`, so the rule that an
  explicit module beats `--all` is not spelled here; `command(boolean execute)` spells `botmaker release …`
  rather than `./release.sh …`, because the line an operator copies has to reach the same code the button
  does. **`--dry-run` is gone from this module's vocabulary entirely** — it was appended to every command
  line with no way to leave it off, and that was the whole of the safety story while the window shelled.

- **`ui/ReleaseTab` — Execute, and the three things guarding it.** Armed by comparing `ReleaseSpec` values
  against the spec of the last clean preview, not by setting a boolean: a boolean stays true after the
  operator ticks another module, which is exactly the case worth refusing. A confirmation listing every
  module and version about to be tagged, whose own button stays disabled until `release` is typed. And a
  release arms nothing afterwards. Output streams line by line into the pane as the run produces it.

- **`.danger` in `dashboard.css`** — outlined rather than filled, because the button spends most of its
  life disabled and a filled button at half opacity reads as broken rather than as waiting.

- **Tests**: `ReleaseRunTest` (a directory that is not a checkout is reported rather than thrown; the
  stream and the kept text are one transcript; a preview writes nothing into the checkout) and
  `ReleaseSpecTest` rewritten around the new shape — including that two specs are equal exactly when they
  would release the same thing, which is what arms the button. 74 tests, headless.

**Done, later the same day — the script became a wrapper, which took this window's last subprocess with it**

- **`release.sh` is ~230 lines** (was 2,214) over `botmaker release`, and `.github/workflows/release.yml`
  still calls it — for `--ci` alone, which prepares a runner and is genuinely bash. The parity diff was
  re-run first, as `--all --sdk 1.2.0` over the real eleven-repository tree, because it is the check that
  cannot be run once both sides are the same code; it agreed on every decision and every write.

- **`ReleaseLog.repoll` calls `ReleaseStatus.repoll`** instead of shelling `./release.sh --status <file>`,
  and returns a `Repoll` value the way `ReleaseRun` returns one. This was the deferred item below and the
  wrapper forced it: shelling would now build a jar to run the code already on this classpath. The lines
  stream into the status label as each module is polled — it is minutes long, so it says what it is doing.

- **`Proc` runs one program now: `git`.** Its javadoc says so, because "every question is answered by git
  or by `release.sh`" was the old shape of the rule and a subprocess is no longer how the release is asked.

- **`ReleaseLogTest`**: a re-poll of a log that is not there is reported, not thrown. 75 tests.

**Deferred / next**

- ~~**`release.sh` and `.github/workflows/release.yml` still have their own implementation.**~~ **Done the
  same day.** The parity diff was re-run first, as `--all --sdk 1.2.0` over the real tree, because it is the
  check that cannot be run once the script is a wrapper; it agreed on every decision and every write. The
  script is ~230 lines and keeps only what is not a decision — the spelling, `--ci`'s runner preparation,
  the `BOTMAKER_RELEASE_TOKEN` precondition, and *no `--dry-run` means `--execute`*.
- ~~**The Releases tab still shells**~~ **Done the same day, and forced by the line above**: with the script
  a wrapper, shelling `--status` would build a jar to run the code already on this classpath. Re-poll calls
  `ReleaseStatus.repoll` and streams its lines. `Proc` runs one program now — `git`.
- **Nothing here has cut a real release yet.** The execute path is exercised by no test — a test that
  pushed a tag would be a test that cannot be re-run — so the first use is a watched one.
- **The Modules tab says less than it did**: it no longer reports gate verdicts, because the gates belong
  to a release rather than to a scan and cost Maven. If that turns out to be missed, the place for them is
  a button rather than the scan.

---

## 2026-09-16 — the Catalog tab: what is published, which the queue cannot say

**Done**

- **`github/Catalog`** — every **merged** entry on both data repositories, as one list. `Kind.PLUGIN` reads
  `plugins/*.json` from `botmaker-plugin-registry` and `Kind.BOT` reads `bots/*.json` from
  `botmaker-gallery`; a gallery entry carrying `GalleryEntry.TEMPLATE_TAG` reads as a **Template** rather
  than a Bot. One request per repository for the directory listing, then one per entry for its bytes — the
  same `2n+2` shape `Queue.open` has, and small by construction.
- **`ui/CatalogTab`** — the rows, the entry as fields through the existing `EntryFields`, and a status line
  counting plugins, bots, templates and unreadable entries separately. Fifth tab, after Queue.
- **`github/Contents`** — the contents API and its base64, extracted from `Queue`. Both readers want the
  same bytes one ref apart: `Queue` at a pull request's head, because the file does not exist on `main`
  yet; `Catalog` on `main`, because the point is that it was merged. The token rule ("reads work signed
  out, writes do not") had been written twice and is now written once.
- **`CatalogTest`** — sixteen cases over the reading and the branch naming, with no network and no
  JavaFX, which is the module's own rule: everything with a rule in it is a pure function over text.

**Why it exists at all.** The Queue tab is what is *waiting* — open pull requests, which is zero most days.
The operator asking "what can somebody install" got an empty window and no way to tell that from a broken
one. The registry holds one entry and the gallery five, two of them templates; none of it was visible.

**Three decisions worth not re-litigating.**

1. **It reads github.com, never the checked-out submodule.** The tabs beside it read the umbrella checkout
   because a release only exists in a working copy. A merged entry exists on `main`, and the umbrella's
   recorded pointer trails it whenever either repository's CI commits a regenerated index — both pointers
   were behind on the day this was written, which is how the hazard was noticed rather than guessed. A
   stale catalog looks exactly like a current one.
2. **It reads the entry files, not the generated `index.json`.** The index is derived by a job; the files
   are what the repository holds, and one file per entry is what makes two same-day submissions
   conflict-free and makes git itself refuse a second claim on an id.
3. **An entry that will not parse is a row, never a dropped one.** It is on `main`, so somebody merged it
   and the gate either passed it or never ran — which makes it precisely the entry an operator has to see.
   It keeps its filename as its identity and its raw text still reaches the field view.

**Nothing here judges an entry.** Everything listed is merged, and what admitted it was `RegistryGate`'s
check run on the pull request that added it. The typed halves are `botmaker-cli`'s own `RegistryEntry` and
`GalleryEntry`, read through `Registry.mapper()` (unknown keys ignored), so a field added to the entry shape
tomorrow lists today instead of reading as a corrupt file.

**Also done, the same day — Edit and Unpublish**

- **`Catalog.edit` / `Catalog.unpublish`** — branch `main`, `PUT` or `DELETE` the one entry file on that
  branch, open a pull request. Neither touches `main`; merging is a decision taken where every other
  submission is decided, with `RegistryGate` having run over the result. Both gated on `Admin.canWrite`,
  which is a courtesy and never a boundary — GitHub answers 403 regardless.
- **`Contents.put` / `Contents.delete`** beside the read, so one URL builder escapes the path for both.
  `GitHubClient.delete(url, body, token)` already existed for exactly this: the contents API's deletion
  needs `message`, `sha` and `branch`, and `HttpRequest.DELETE()` sends no body.
- **No fork**, which is the difference from `PluginPublishCommand`: that command forks because a submitter
  usually cannot push to the registry, and GitHub will not fork a repository into the account that owns it.
  An operator with `permissions.push` pushes the branch directly.
- **The blob `sha` goes back with the write**, so GitHub refuses it if the file moved since it was read.
  Optimistic locking rather than last-one-wins — two operators editing one entry is the case
  one-file-per-entry was shaped to make visible.
- **The branch carries a UTC timestamp and the id is reduced to legal ref characters.** Without the
  timestamp, a second edit while the first pull request is open is a 422 naming an existing ref, which
  reads as a bug here; without the reduction, a bot's `owner/repo` id puts a second segment in the name.

**Two things that look like gates and are not.** The edit dialog says whether the text parses and does
**not** refuse it — whether an entry is good is `RegistryGate`'s answer, and a syntax opinion here is the
first step towards a second gate. An edit that changed nothing opens no pull request, which is arithmetic.
Unpublish asks the operator to **type the id**, because merging it removes the entry for everyone and the
filename is the claim; that is a confirmation, not a judgement. The editor is a text area over the JSON and
not a form, for `EntryFields`' own reason: a form shows only the keys it was written to know about.

**The catalog is not reloaded after a proposal.** Nothing published has changed. The proposal is in the
Queue tab, with the gate's verdict against it.

**Deferred / next**

- **Nothing verifies the write path end to end.** `CatalogTest` covers the branch naming, the sha carried
  with the entry and the reading; the four HTTP calls are only exercised by actually opening a pull request
  against a live data repository. A fake `GitHubClient` would be the way, and `GitHubClient` is a concrete
  class in `botmaker-shared` with no interface to stub — extracting one is a shared-module change and was
  not made for this.

---

## 2026-09-05 — the version/level picker, and the first call into the release library

**Done**

- `umbrella/VersionTargets`: `latest` (a module's newest tag) and the two arrows, `forLevel` and `forExact`.
  Every one of them delegates to `com.botmaker.cli.release` — `Tags.latest`, `VersionSpec.parse` and
  `against`, which are `release.sh`'s `latest_version` and `resolve_version` ported in Part C slice 1.
- `ReleaseTab.Row` now holds a `Level` (always set, since a bare module flag means `patch`) plus a separate
  typed version behind an `exactChosen` flag, so switching between them loses neither. `SpecCell` is the
  segmented control; a cleared toggle group is put back, because "asked for at no level" is a state the
  script does not have.
- A **Would cut** column, live: the tags are read once per preview off the FX thread, one `Platform.runLater`
  per module so the table fills in as each answers rather than all at the end, and every level click after
  that is pure.
- `pom.xml` takes `botmaker-cli`'s **main** artifact with `botmaker-studio-api` and `botmaker-plugin-host`
  excluded. Verified by `dependency:tree`: the CLI arrives as a leaf, and picocli never arrives at all
  (it is `optional` there, which is the point of that declaration).
- `VersionTargetsTest` (5): the three levels off a real tag, a module with no tag reading `no tag → 0.0.1`
  rather than `0.0.0 → 0.0.1`, an exact version passing through even when it goes backwards (that refusal is
  the release's judgement, not this window's), a half-typed `1.2` saying *not a version*, and the data
  repositories having no arrow at all.

**Deferred / next**

- **The Catalog tab** — the published gallery and plugin-registry indexes, read-only, with *Delist* opening
  a pull request that deletes the one entry file (never a direct commit to `main`), gated on the same
  `permissions.push` the queue uses.
- **A Doctor tab** — git identity, the release token's scope across all eleven repositories, `mvn`, JitPack
  reachability: the environment `--ci` refuses on, answered before a release rather than during one.
- **Drift alerts on the Modules tab** — one *what is owed* line rolling up stale pins, missing
  `[Unreleased]` sections and tags whose JitPack or Actions verdict was never green.
- **A per-module diff viewer** — the commits and files since a module's last tag, with the
  release-irrelevant ones greyed out, so *why is this releasing* is answerable without a terminal.
- `--all <level>` is still a `ComboBox` with no arrow beside it. It would need ten arrows, one per module,
  and the honest place for them is the rows themselves once *explicit beats `--all`* is shown there too.

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
  re-poll is a reviewable diff, and committing it stays the operator's call. *(2026-09-16: it calls
  `ReleaseStatus.repoll` directly. Same question, same owner, no subprocess.)*
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
