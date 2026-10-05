# CLAUDE.md

Guidance for Claude Code working in **`botmaker-dashboard`** — the maintainer's window onto the release
constellation and the submission queue. The umbrella's `../CLAUDE.md` is the map of how the modules fit
together; this file is what is true inside this one.

The design, its history and the reasons behind it are in `docs/architecture/`, one file per section (moved
there unchanged on 2026-10-05).

## Read before touching

| Touching | Read (`docs/architecture/`) |
|---|---|
| anything that previews, cuts or re-polls a release; `ReleaseRun`, `ReleaseJob`, `VersionTargets`, `CiStatus`, `Verdicts` | `release-rule.md` |
| the Changelog tab, `ChangelogEdit`, `ChangelogDrafts`, Draft with Claude, `CswapAccounts` | `changelog-tab.md` |
| `Admin`, who may write | `admin.md` |
| the Queue tab, `Submission`, `Checks`, `EntryFields` | `queue.md` |
| the Catalog tab, `Catalog`, `Contents` | `catalog.md` |
| Edit, Unpublish, any write to a data repository | `editing-and-unpublishing.md` |
| the Tier column, `Vetting`, the Auto-merge column | `tiers-and-vetting.md` |
| the package tree, `ReleaseSpec`, `dashboard.css`, `Themed`, `Browse` | `layout.md` |
| `AccountBar`, the stylesheet, anything copied from Studio | `duplication-from-studio.md` |
| `-Pdist`, the rpm/deb, `BuiltWith`, `.deps.env` | `packaging.md` |

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

## Rules

- **Never reimplement a decision the release owns.** Which modules a release cuts, versions, forcing, tag
  order and every gate belong to `com.botmaker.cli.release`; this app calls it. The test for anything new:
  would this window otherwise have to *decide* something? Then it calls the library (`release-rule.md`).
- **A submission's verdict is the registry CI's check run** (`RegistryGate`); read it, validate nothing here.
  The one judgement made here is the one-file shape of a pull request (`queue.md`).
- **Every write to a data repository is a pull request**, never a push to `main` (`editing-and-unpublishing.md`).
- **Admin is GitHub's `permissions.push`, never a list here**, and a refusal is not a security boundary (`admin.md`).
- **`umbrella/` and `github/` hold no JavaFX**: a rule in a `ui/` class is a rule that will not be tested.
- **A colour literal in a CSS rule is a bug**; every `Dialog`/`Alert` goes through `Themed.dialog(dialog,
  owner)`. **Never call `java.awt.Desktop`**: `ui/Browse` is the one way to open a URL (`layout.md`).
- *Duplicate presentation freely, never logic that can be wrong* (`duplication-from-studio.md`).

## Commands

```bash
mvn -pl botmaker-dashboard -am install    # from the umbrella root; -am builds shared and the cli first
mvn -pl botmaker-dashboard javafx:run
mvn -pl botmaker-dashboard test
JAVA_HOME=~/.jdks/<an upstream JDK> mvn -pl botmaker-dashboard -Pdist package -DskipTests -Dapp.version=0.0.1
                                          # target/dist: the app-image, the rpm and the deb (Linux; needs
                                          # rpmbuild + fakeroot; Fedora's patched JDK refuses jlink)
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
