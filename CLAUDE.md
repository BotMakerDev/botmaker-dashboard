# CLAUDE.md

Guidance for Claude Code working in **`botmaker-dashboard`** — the maintainer's window onto the release
constellation and the submission queue. The umbrella's `../CLAUDE.md` is the map of how the modules fit
together; this file is what is true inside this one.

## What it is, and the two things it is not

A JavaFX desktop app that reads the **umbrella checkout** and the **GitHub API**, and shows four things:
what each module's tag and pins look like, what the last releases did, what a release *would* decide, and
which submissions are waiting on a verdict.

**It is not a service.** There is no server, no scheduler and no state of its own beyond one remembered
path. Everything about releases lives in a working copy — `release.sh`, `.gitmodules`, each submodule's
git, the committed `releases/*.md` — so a page could not answer any of it.

**It is not a second Studio.** It depends on `botmaker-shared` and on nothing else of ours: no plugin
contract, no plugin-host, no SDK, and above all not `botmaker-studio`, which is an application and cannot
be depended on. It loads no plugin and opens no bot project.

## The rule the whole module hangs on

**Never reimplement a decision `release.sh` owns.** Which modules a release cuts, what version each gets,
what forces what, the tag order, and every gate — one implementation, and this app is not it. Shell to the
script and read its output: `--dry-run` for a plan, `--status <file>` for a re-poll.

The reason is the one the script's own header records for `--ci`: a second implementation diverges on the
first rule added, and the divergence is discovered as a **bad tag**, which cannot be edited. So the moment a
computation appears here that the script could have answered, that is the bug — not a shortcut.

**Part C of the plan replaces the implementation without weakening the rule.** `release.sh` becomes
`com.botmaker.cli.release`, a library the terminal, `release.yml` and this app all call; this app then swaps
parsed stdout for typed objects. Until that exists, **this window visualises and does not execute** — no
tag is pushed from a GUI.

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

## Layout

```
com.botmaker.dashboard
├── DashboardApp        the window: top bar + four tabs. The JavaFX entry point.
├── DashboardConfig     the one remembered preference (the umbrella path) + looksLikeUmbrella
├── github/
│   └── Admin           permissions.push, and every failure folded into read-only
└── ui/
    ├── UmbrellaBar     the checkout in use, and the picker that refuses a wrong directory
    └── AccountBar      the OAuth device-flow control (the flow itself is shared's)
```

`src/main/resources/css/dashboard.css` is the whole look, one theme, tokens on `.root`.

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
`DashboardConfig.looksLikeUmbrella`) is a pure function over JSON or a path, and the JavaFX classes hold no
rules. Keep it that way — a rule that can only be tested by showing a window is a rule that will not be
tested.

## Code style

The umbrella's *Code style* applies. Two things bite here specifically:

- **Nothing may touch the scene off the FX thread.** `GitHubAuth.pollForToken` supplies its own background
  thread and `GitHubClient` is async throughout, so every `thenAccept` that writes to a control wraps in
  `Platform.runLater`.
- **Degrade to a sentence, never to an empty window.** An unreachable GitHub, a checkout that has moved, a
  `release.sh` that exits non-zero — each is a line the operator can act on. This app's whole job is
  telling somebody what is wrong; failing silently is the one thing it must not do.
