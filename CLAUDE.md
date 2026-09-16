# CLAUDE.md

Guidance for Claude Code working in **`botmaker-dashboard`** — the maintainer's window onto the release
constellation and the submission queue. The umbrella's `../CLAUDE.md` is the map of how the modules fit
together; this file is what is true inside this one.

## What it is, and the two things it is not

A JavaFX desktop app that reads the **umbrella checkout** and the **GitHub API**, and shows five things:
what each module's tag and pins look like, what the last releases did, what a release *would* decide,
which submissions are waiting on a verdict, and **what is already published**.

**It is not a service.** There is no server, no scheduler and no state of its own beyond one remembered
path. Everything about releases lives in a working copy — `release.sh`, `.gitmodules`, each submodule's
git, the committed `releases/*.md` — so a page could not answer any of it.

**It is not a second Studio.** It depends on `botmaker-shared` and — since 2026-09-05 — on `botmaker-cli`'s
**main** artifact, and on nothing else of ours: no plugin contract, no plugin-host, no SDK, and above all not
`botmaker-studio`, which is an application and cannot be depended on. It loads no plugin and opens no bot
project. The contract and plugin-host are `<exclusions>` on the CLI dependency rather than absences that
happen to hold: the CLI is a plugin host and pins both, this window hosts nothing, and excluding them turns
"`com.botmaker.cli.release` needs the contract" into a compile error here rather than a silent new edge.

## The rule the whole module hangs on

**Never reimplement a decision the release owns.** Which modules a release cuts, what version each gets,
what forces what, the tag order, and every gate — one implementation, and this app is not it.

The reason is the one `release.sh`'s own header records for `--ci`: a second implementation diverges on the
first rule added, and the divergence is discovered as a **bad tag**, which cannot be edited. So the moment a
computation appears here that the owner could have answered, that is the bug — not a shortcut.

**Since 2026-09-16 that owner is `com.botmaker.cli.release` and this app calls it.** It shelled to
`./release.sh --all --dry-run` and read module verdicts back out of its stdout until then — which kept the
rule by keeping the decisions *out of reach*, behind a pipe and a regular expression, and cost a parser that
could mis-read a line the script reworded. The library has three callers now (`botmaker release`,
`.github/workflows/release.yml`, this window) and the rule is the strict form of the same sentence: **one
implementation, and every caller reaches it.** `umbrella/ReleasePlan` is deleted; `ModuleScan` calls
`Plan.decide` and `ReleaseTab` calls `Release.run` through `umbrella/ReleaseRun`.

**So a tag *is* pushed from this GUI now, and the paragraph that said otherwise is gone.** What replaced it
is not a weaker rule but a different guard, and it is worth stating where the old one was:

- **A preview and a release are one call with a different `Runner`.** That is `Release`'s own design and it
  is the property a shelling window could not have: the plan on screen was produced by the code that will do
  the work, so it cannot drift from it.
- **Execute is armed by value, not by a flag.** It is dead until a preview has run *in this session, with
  these exact flags, against this checkout* and returned no refusal. `ReleaseSpec` is a record, so
  `equals` answers "the same flags" and a tick or a keystroke takes the button dead again. A boolean would
  stay true after the operator changed something, which is the one case worth refusing.
- **Then a confirmation that lists every module and version about to be tagged**, and will not enable its
  own button until `release` is typed. Same reasoning as the Catalog tab's Unpublish: a dialog one click
  from done is a dialog people dismiss.
- **A release never arms anything.** Whatever it leaves behind, the way to get the button back is to preview
  again against the checkout as it now is.
- **A release runs in a process of its own, a preview does not** (since 2026-09-16). The first release cut
  from this window ran in its JVM and died with it, four tags in. Execute starts `umbrella/ReleaseJob` through
  `ReleaseLauncher` (`setsid` on Linux); the tab only *watches* `releases/.running/<stamp>.out` and the release
  log, through `ReleaseProgress`, and reattaches to a live job when reopened. The child is `ReleaseRun.go`
  and nothing else — a second code path to the library there would be the thing this section forbids.

**The first piece of that library arrived early, and it is the shape every later one takes.** The Release
tab's level picker shows what a level resolves to — `1.1.6 → 1.2.0` — and that arrow is `latest_version` and
`resolve_version` themselves, called through `umbrella/VersionTargets`. It reads as an exception to the rule
and is the strict form of it: the alternative to calling the owner is either a bump computed here (a second
implementation, discovered as a bad tag) or an operator choosing `minor` with no way to see what `minor`
means for that module today. **The test to apply to the next one is the same**: would this window otherwise
have to *decide* something? Then it calls the library. Is it merely presentation? Then it stays here.

**The re-poll button is the worked example.** Asking JitPack for a `.pom` with a HEAD request would be four
lines here and would answer a *different question* than the release asked: `CleanRoom` runs a real
`dependency:resolve` into a throwaway repository, which is the only thing that catches a published pom
naming a dependency nobody can resolve — the `0.0.0-SNAPSHOT` bug that shipped in every SDK up to v1.0.24.
A cheaper check here would turn a broken row green. So writing back to a log calls `ReleaseStatus.repoll`
and re-reads the file it rewrote. It shelled to `./release.sh --status <file>` until 2026-09-16, which kept
the same property by keeping the readers out of reach; the script is a wrapper now, so shelling would build a
jar to run the code already on this classpath.

**The Releases tab does send that HEAD now (2026-09-16), and it keeps the rule by its words.** A history of
eighty releases cannot run a forty-second clean room per module on open, so `umbrella/Verdicts.jitpackHead`
asks for the `.pom` and says `published (pom HEAD)` or `missing (pom HEAD)` — never `ok`, which stays the
clean room's word, reached through *Deep check*. Both are green on a lane; the lane's line says which answer it
is and how old. **A quick question is allowed; a quick question wearing the release's verdict is not.**

**The CI badge on the Modules tab is the same rule once more, and it is the strictest case yet.** What
refuses a red module's release is `CiGate`, so the badge *is* that gate's verdict — `umbrella/CiStatus` calls
`CiGate.check(module, false)` and renders the `GateVerdict` it gets back, keeping the gate's own sentence.
A badge that read `gh run list` itself would eventually disagree with the gate, and the operator would see
green on one tab and a refusal on another with nothing to say which asked the right question. `force` is
always `false` here: forcing belongs to a release being cut, and a badge that went quiet because somebody
ticked `--force` elsewhere would be reporting an intention rather than a build. A module the release never
cuts is asked nothing at all, which is also why no `ci.yml` is ever requested from `botmaker-gallery`.

The same rule covers the queue. A submission's verdict is **the registry CI's check run**, which runs
`RegistryGate` from `botmaker-cli`'s main artifact. Read that verdict; validate nothing here. *The check
that refuses a pull request must be the one its author already ran* — the whole reason that validator is a
library rather than part of a command.

## Admin — GitHub answers it, and there is no second list

`Admin.probe` reads `permissions.push` from `GET /repos/LiQiyeDev/botmaker-plugin-registry` for the
signed-in account. That bit enables the write actions. Nothing else is consulted.

An allowlist in this repository was considered and refused. It would be a second statement of a fact that
lives on github.com, in a place with no way to notice the first one changing — someone removed from the
project keeps the buttons, someone added waits for a release. And it could not be made good on: approving a
pull request needs the push right *at the moment of the call*, so an app trusting its own list produces a
403 in a dialog instead of a disabled button.

**So a refusal here is not a security boundary and must never be written as one.** The API still decides.
What the verdict buys is that the operator learns they cannot merge before writing a review, not after.

Every probe failure — offline, not signed in, a narrowed token scope, a repository the account cannot see —
is **read-only with a reason**, never an error dialog. The window stays fully usable for everything it
reads, which is most of it.

## The queue — one judgement, and it is about shape

A submission is a pull request adding **one file**: `plugins/<plugin-id>.json` or
`bots/<owner>-<repo>.json`. Everything about whether it is *good* belongs to the registry's CI, which runs
`RegistryGate` from `botmaker-cli`'s main artifact — the same code its author ran as
`botmaker plugin validate`. The Gate column is that check run's conclusion, in GitHub's own words.

**The one thing judged here is that shape**, because it is a property of the layout rather than of a plugin:
one file per entry is what makes two same-day submissions two files that cannot conflict, what makes git
itself refuse a second claim on an id, and what lets `index.json` be generated rather than edited. So a pull
request touching anything besides its own entry is flagged loudly and **Merge is refused locally** —
`index.json` by hand is the common case, and it is stale the moment another submission merges. Two entry
files is *no* entry file: which id is being claimed has no answer, so there is nothing safe to merge.

**`Checks.NONE` is where this deliberately differs from `ReleaseLog.Health`.** The release log reads
`no run on <tag>` as **broken**, because a tag is finished and nothing more will fire. A pull request is not
finished — one opened a minute ago simply has no check run yet. Same JSON, opposite verdict, because the
subject's lifecycle differs; a shared classifier here would have to be wrong for one of the two.

**`EntryFields` reads the file, never a schema.** What an entry must contain has one owner and it is the
gate. A copy of it here would go stale on the first field added and, worse, would *hide* a key a submission
carries that this window has never heard of — which is exactly the key a reviewer needs to see.

## The catalog — what shipped, which the queue cannot answer

**The Queue tab lists open pull requests, and that is usually zero.** It is the truthful answer to *what is
waiting on me* and no answer at all to *what can somebody install*. `Catalog` is the second question: every
**merged** entry — `plugins/<plugin-id>.json` and `bots/<owner>-<repo>.json` — with the gallery entries
carrying `GalleryEntry.TEMPLATE_TAG` shown as templates rather than bots.

**It reads github.com, not the checked-out submodule**, and that is the one decision in it. The umbrella has
both data repositories as submodules, and `ModulesTab`/`ReleasesTab` beside it read the checkout — but they
do so because a release only exists in a working copy. A merged entry exists on `main`; the umbrella's
recorded pointer trails it every time either repository's CI commits a regenerated index (both pointers were
behind on 2026-09-16, which is how this was noticed), and a stale catalog is indistinguishable from a
current one.

**It reads the entry files and not the generated `index.json`**, for the reason the layout exists: one file
per entry is what makes two same-day submissions two files that cannot conflict and what makes git itself
refuse a second claim on an id. The index is derived from them by a job, so reading it shows what the job
last produced rather than what the repository holds.

**The typed halves are the CLI's records** — `RegistryEntry`, `GalleryEntry`, `Registry.ENTRIES_DIRECTORY`,
`Registry.INDEX`, `GalleryEntry.TEMPLATE_TAG` — read through `Registry.mapper()`, which disables
`FAIL_ON_UNKNOWN_PROPERTIES`. Both are pure Jackson records naming no contract type, so they are safe under
this pom's `<exclusions>`. A field added to the entry shape tomorrow therefore lists today, and still reaches
the field view, because `EntryFields` reads the raw text beside it.

**An entry that will not parse is a row, never a dropped one.** It is on `main`, so somebody merged it and
either the gate passed it or never ran; it keeps its filename as its identity, is counted separately in the
status line, and its bytes still reach `EntryFields`. Burying it in a total is how it stays unnoticed.

**`Contents` is the one HTTP shape both readers share.** `Queue` wants the entry a pull request adds, at
that pull request's head; `Catalog` wants the entry that is merged, on `main`. One ref apart — so the
request, the base64 decode and the "reads work signed out" token rule live in one place rather than two.
The writes are there too, for the same reason: one URL builder, so a path that escapes correctly for a read
escapes correctly for the write after it.

## Editing and unpublishing — pull requests, never `main`

**`Catalog.edit` and `Catalog.unpublish` open a pull request and change nothing else.** Branch `main`,
`PUT` or `DELETE` the entry file on that branch, open the pull request. Merging is somebody's decision,
made where every other submission is decided.

**Why not a push to `main`.** The entry file is the source of truth and `index.json` is generated from it by
CI, so a commit straight to `main` leaves an index that disagrees with the entries until the next job runs —
which is the same hazard as a hand-edited `index.json`, from the other end. And a pull request runs
`RegistryGate` over the *result*, which is the only way an edit gets the same check a submission gets.

**No fork, and that is the difference from `PluginPublishCommand`.** That command forks because a submitter
usually cannot push to the registry; an operator with `permissions.push` can, so the branch goes straight
there. Reusing that command's flow would mean shelling to `gh` from a GUI and forking the maintainer's own
repository, which GitHub refuses anyway.

**The blob `sha` from the listing is sent back with the write.** GitHub then refuses it if the file moved
since it was read — optimistic locking, not a courtesy: two operators editing one entry is exactly the case
one-file-per-entry exists to make visible.

**The branch name carries a UTC timestamp**, and the id is reduced to the characters a git ref may hold.
Without the timestamp a second edit while the first pull request is open is a 422 naming an existing ref,
which reads as a bug in this window; without the reduction, a bot's `owner/repo` id would put a second
segment in the branch name.

**Two things the dialogs do that are not gates.** The edit dialog says whether the text parses and does
**not** refuse it — whether an entry is good is `RegistryGate`'s answer, and a syntax opinion here is the
first step towards a second gate. An edit that changed nothing opens no pull request, which is arithmetic
rather than judgement. Unpublish asks the operator to **type the id**, because merging it removes the entry
for everyone and the id is the one thing re-submitting cannot recover — the filename is the claim.

**It is a text area over the JSON, not a form.** A form shows only the keys it was written to know about,
so it would silently drop one an entry carries that this window has never heard of. Same rule as
`EntryFields`.

**The catalog is not reloaded after a proposal**, and that is the honest thing: nothing published has
changed. The proposal is in the Queue tab now, with the gate's verdict against it.

## Layout

```
com.botmaker.dashboard
├── DashboardApp        the window: top bar + four tabs. The JavaFX entry point.
├── DashboardConfig     the one remembered preference (the umbrella path) + looksLikeUmbrella
├── github/            everything read from the API — no JavaFX either, and tested the same way
│   ├── Admin           permissions.push, and every failure folded into read-only
│   ├── Queue           the open pull requests on both data repos, and the four writes
│   ├── Catalog         the MERGED entries on both data repos, and the two writes over them (as PRs)
│   ├── Contents        the contents API — read, put, delete — shared by Queue (at a head) and Catalog (main)
│   ├── Submission      one pull request: who, what one file it adds, and what it must not add
│   ├── Checks          the gate's own check-run conclusion, reduced to one verdict and one line
│   └── EntryFields     an entry, flattened into rows — read from the file, not a schema
├── umbrella/           everything read out of the checkout — no JavaFX, all of it testable
│   ├── Proc            one external command, output captured, a timeout that is a result
│   ├── Umbrella        the module list, read from .gitmodules and never kept here
│   ├── DepsEnv         the pins, plus the one question the file cannot ask: is this one stale?
│   ├── Changelog       is there an [Unreleased] section for a release to stamp
│   ├── ModuleRow       one module as a row
│   ├── ModuleScan      git per module, then Plan.decide once, into rows
│   ├── ReleaseLog      one releases/*.md: the table, the errors, and --status to re-poll it
│   ├── ReleaseRun      one release, previewed or cut — the only place here that can push a tag
│   ├── ReleaseJob      main(): ReleaseRun.go in a child process, every line stamped, a last release-job: line
│   ├── ReleaseLauncher starts ReleaseJob (setsid), and finds a job again from releases/.running/
│   ├── ReleaseProgress where a running release is — lanes, tiles, phase — from its output and its log
│   ├── ReleaseSpec     what was ticked, as the request Plan.decide takes, as two command lines, and back
│   ├── VersionTargets  what a level would cut, asked of com.botmaker.cli.release and never computed here
│   ├── ReleaseHistory  releases from tags: 15-min gap or a repeated module starts a new one; logs laid over
│   ├── Verdicts        pom HEAD ("published", never "ok"), CleanRoom as deep check, Actions.poll
│   ├── VerdictCache    releases-cache.json under CacheDirs; settled verdicts are not asked again
│   ├── CiStatus        what CI says about main — CiGate's verdict rendered, never a second read of gh
│   └── Links           a tag's three pages (Release, JitPack, Actions), and a repository's four
└── ui/
    ├── UmbrellaBar     the checkout in use, and the picker that refuses a wrong directory
    ├── AccountBar      the OAuth device-flow control (the flow itself is shared's)
    ├── Browse          open a URL off the FX thread (platform opener, then HostServices) — never AWT
    ├── Themed          the palette on every window's scene root, and the owner on every dialog
    ├── ModulesTab      the rows, in a table, with a refresh that runs off the FX thread
    ├── ReleasesTab     every release from tags, drawn as a board; write back = ReleaseStatus.repoll
    ├── ReleaseTab      a row per module, Preview in-process, Execute as a child watched on a board
    ├── widgets/        SummaryTiles, ModuleLane, ReleaseTimeline, LiveBadge, ReleaseBoard, LinkBar — draw,
    │                   never count
    ├── QueueTab        the submissions, the entry as fields, and the writes gated on Admin.canWrite
    └── CatalogTab      what is published, counted by kind, with Edit and Unpublish gated on Admin.canWrite
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

## Why some things are duplicated from Studio, and one thing is not

**`AccountBar` is a second implementation of the *control*, never of the *flow*.** The device-flow
handshake, the poll, the token file and its `0600` are `com.botmaker.shared.github.GitHubAuth` — that
package left Studio on 2026-09-05 for this module. What is written here is four buttons and an alert,
because Studio's bar is themed by Studio's `BlockTheme`, hangs off Studio's dialogs and answers to Studio's
window. A widget with two owners is how this module would acquire a dependency on an application.

**The stylesheet is likewise its own.** Copying four colours is cheaper than depending on an app, and
Studio's tokens are named for a block canvas this window does not have.

**What must never be copied is a decision.** A release rule, a gate, a version bump, a verdict on a
submission — those have one owner each (`release.sh` today, `com.botmaker.cli.release` after Part C, and
`RegistryGate` for the gate). The line to hold: *duplicate presentation freely, never logic that can be
wrong.*

## Not published, and what that omits on purpose

No tag, no JitPack build, no GitHub Release, no `.deps.env`, **no flatten**. Nothing resolves this module
as a dependency, so there is no published pom for a `-D` to be missing from. `pom.xml` says so where the
flatten would go.

**If it is ever published, add both in the same commit.** A published pom carrying
`botmaker-shared:0.0.0-SNAPSHOT` is exactly the bug that shipped in every SDK up to v1.0.24 — see the
umbrella's *JitPack coordinate model*.

## Commands

```bash
mvn -pl botmaker-dashboard -am install    # from the umbrella root; -am builds shared first
mvn -pl botmaker-dashboard javafx:run
mvn -pl botmaker-dashboard test
```

Tests are headless by construction: everything with a rule in it (`Admin.read`,
`DashboardConfig.looksLikeUmbrella`, `ReleaseProgress.of`) is a pure function over JSON, text or a path, and
the JavaFX classes hold no rules. Keep it that way — a rule that can only be tested by showing a window is a
rule that will not be tested.

**Drawing is tested too, since 2026-09-16**, with TestFX over Monocle's Headless platform (Studio's pairing:
monocle 21.0.2 on JavaFX 25; Surefire sets the properties, `ui/FxHeadless` repeats them for an IDE run). Those
tests assert what a model *looks like* — node style classes, a banner's words, a click that ticks a row —
never a rule; the rule they draw was already tested without a window.

## Code style

The umbrella's *Code style* applies. Two things bite here specifically:

- **Nothing may touch the scene off the FX thread.** `GitHubAuth.pollForToken` supplies its own background
  thread and `GitHubClient` is async throughout, so every `thenAccept` that writes to a control wraps in
  `Platform.runLater`.
- **Degrade to a sentence, never to an empty window.** An unreachable GitHub, a checkout that has moved, a
  `release.sh` that exits non-zero — each is a line the operator can act on. This app's whole job is
  telling somebody what is wrong; failing silently is the one thing it must not do.
