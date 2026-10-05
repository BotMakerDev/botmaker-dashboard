# Layout

```
com.botmaker.dashboard
├── DashboardApp        the window: top bar + four tabs. The JavaFX entry point.
├── Launcher            the shaded jar's and the app-image's main class (does not extend Application)
├── DashboardConfig     the one remembered preference (the umbrella path) + looksLikeUmbrella
├── github/            everything read from the API — no JavaFX either, and tested the same way
│   ├── Admin           permissions.push, and every failure folded into read-only
│   ├── Queue           the open pull requests on both data repos, and the four writes
│   ├── Catalog         the MERGED entries on both data repos, their vetted/ records, and two writes (as PRs)
│   ├── Vetting         vet a bot at a release, or revoke it — vetted/ in the gallery, as a pull request
│   ├── Contents        the contents API — read, put, delete — shared by Queue (at a head) and Catalog (main)
│   ├── Submission      one pull request: who, what one file it adds, and what it must not add
│   ├── Checks          the gate's own check-run conclusion, reduced to one verdict and one line
│   └── EntryFields     an entry, flattened into rows — read from the file, not a schema
├── umbrella/           everything read out of the checkout — no JavaFX, all of it testable
│   ├── Proc            one external command, output captured, a timeout that is a result; 8 at once at most;
│   │                   an interrupt kills it, runToTheEnd (a commit) is out of an interrupt's reach
│   ├── Io              the one executor (virtual threads) for blocking work, parallel(), a lock per repository,
│   │                   Task: what a Cancel button stops (fail the future, then interrupt)
│   ├── Umbrella        the module list, read from .gitmodules and never kept here
│   ├── DepsEnv         the pins, plus the one question the file cannot ask: is this one stale?
│   ├── Changelog       is there an [Unreleased] section for a release to stamp
│   ├── ModuleRow       one module as a row
│   ├── ModuleScan      git per module, then Plan.decide once, into rows
│   ├── ReleaseLog      one releases/*.md: the table, the errors, and --status to re-poll it
│   ├── ReleaseRun      one release, previewed or cut — the only place here that can push a tag
│   ├── ReleaseJob      main(): ReleaseRun.go in a child process, every line stamped, a last release-job: line
│   ├── ReleaseLauncher starts ReleaseJob (setsid), and finds a job again from releases/.running/; refuses a
│   │                   second while one runs; prunes finished jobs' files after a week
│   ├── JobTail         a running job's output, read from where the last read stopped
│   ├── ProgressLine    one stamped line the child wrote, the log it names, and its release-job: ending
│   ├── ReleaseProgress where a running release is — lanes, tiles, phase — from its output and its log
│   ├── PastProgress    a finished release as the same lanes, from its tags, its log and the verdict cache
│   ├── ReleaseSpec     what was ticked, as the request Plan.decide takes, as two command lines, and back
│   ├── VersionTargets  what a level would cut, asked of com.botmaker.cli.release and never computed here
│   ├── ReleaseHistory  releases from tags: 15-min gap or a repeated module starts a new one; logs laid over
│   ├── Verdicts        pom HEAD ("published", never "ok"), CleanRoom as deep check, Actions.poll
│   ├── VerdictCache    releases-cache.json under CacheDirs; settled verdicts are not asked again
│   ├── CiStatus        what CI says about main — CiGate's verdict rendered, never a second read of gh
│   ├── ChangelogEdit   read and rewrite one [Unreleased] section, and commit that one file — no push
│   ├── ChangelogDrafts every module with no section: copied forward or drafted, each committed; Drafter is a seam
│   ├── CswapAccounts   cswap list as slots and 5h usage — a slot, never an address
│   ├── ClaudeDraft     the prompt, the argv and the rotation; it fills the editor and saves nothing
│   ├── BuiltWith       the cli this build was packaged with (baked .deps.env) against the checkout's
│   └── Links           a tag's three pages (Release, JitPack, Actions), and a repository's four
└── ui/
    ├── UmbrellaBar     the checkout in use, the picker that refuses a wrong directory, the stale-cli notice
    ├── AccountBar      the OAuth device-flow control (the flow itself is shared's)
    ├── Browse          open a URL off the FX thread (platform opener, then HostServices) — never AWT
    ├── Themed          the palette on every window's scene root, and the owner on every dialog
    ├── LazyTab         a tab built when first opened (Releases, Changelog, Queue, Catalog)
    ├── ReleaseConfirm  the typed-word confirmation both release buttons show
    ├── ModulesTab      the rows, in a table, with a refresh that runs off the FX thread
    ├── ReleasesTab     every release from tags, drawn as a board; write back = ReleaseStatus.repoll
    ├── ReleaseTab      a row per module, Preview in-process, Execute as a child watched on a board
    ├── ReleaseRowTable its rows: the level picker and the arrow each level resolves to
    ├── ReleaseWatcher  reads a release child once a second and hands the tab what changed
    ├── ReleaseBackend  what the tab calls outside itself — the seam a test replaces
    ├── widgets/        SummaryTiles, ModuleLane, ReleaseTimeline, LiveBadge, ReleaseBoard, LinkBar — draw,
    │                   never count
    ├── ChangelogTab    write the [Unreleased] section, commit it in the submodule, optionally draft it
    ├── QueueTab        the submissions, the entry as fields, and the writes gated on Admin.canWrite
    ├── CatalogTab      what is published, counted by kind, with Edit and Unpublish gated on Admin.canWrite
    └── CatalogDialogs  edit, unpublish, vet, revoke — each asks, and answers the pull request it opens
                        (Update template… and its dialog left on 2026-10-01: a template is cut with --gamebot)
```

**`--dry-run` is not a checkbox, and it is not a flag this module can spell at all.** `ReleaseSpec` appended
it to every command line with no way to leave it off until 2026-09-16, which was the whole of the safety
story while the window shelled. A preview is a `Runner` now, chosen by `ReleaseRun.go`, so the vocabulary
has no such word — and the guard has moved to the arming and the confirmation above, where it can say what
it is refusing and why. `ReleaseSpec` still spells two **command lines**, because the fallback for a window
that cannot finish must reach the same library the button does: `botmaker release …` and the same plus
`--execute`.

**A third list this module does not keep: the module flags.** `--plugin-toolkit` is
`botmaker-plugin-toolkit` without the `botmaker-` prefix, for all eleven, and `Module.flag()` derives it in
the library. The one thing that looks like re-deciding and is not is `wellFormed` — `x.y.z` or
`patch|minor|major` is the *grammar of an argument*, which the library states in its own refusal; what a
level **resolves to** is a bump off that module's latest tag, and it stays the owner's — asked of
`com.botmaker.cli.release` through `VersionTargets`, never worked out here. Which module an explicit flag
beats `--all` for is `Requested`'s, for the same reason.

**`umbrella/` holds no JavaFX and the split is load-bearing**, not tidiness: it is what lets every rule in
this module be a pure function over text a test can hand it, so CI needs no display (see *Commands*). A rule
that arrives in a `ui/` class is a rule that will not be tested.

**There are two lists this module deliberately does not keep.** Which modules a checkout has is
`.gitmodules`' answer (`Umbrella.modules`) — a copy here would be short by exactly one module the day an
eleventh is added, which is the day it matters. And which modules are *releasable* is the library's
`Module` enum: the Release tab lists `Order.TAG` before any preview, and `botmaker-gallery`,
`botmaker-plugin-registry` and this repository are not in it, so the Modules tab says *not released by the
release* rather than inventing a category.

`src/main/resources/css/dashboard.css` is the whole look: two palettes of `-bm-*` tokens on
`.root.theme-dark` / `.root.theme-light`, and rules that read only tokens. **A colour literal in a rule is a
bug** — it is right in one theme by construction. **Every `Dialog`/`Alert` goes through
`Themed.dialog(dialog, owner)`**, and `Themed.install` themes every other `Window` as it appears (context
menus, tooltips, combo popups): each is its own scene, and a stylesheet on the main scene reaches none of
them, which is how dialogs rendered Modena's black on white until 2026-09-16. The choice is remembered in
`DashboardConfig.theme`; `null` follows the desktop's `ColorScheme`.

**Never call `java.awt.Desktop` from this module.** On Linux, initialising AWT inside a running JavaFX
application froze and then killed the window — during an Unpublish, while a release was being cut in the
same JVM. `ui/Browse` is the one way to open a URL.
