# botmaker-dashboard

The maintainer's window onto the BotMaker release constellation, and onto the queue of plugin and bot
submissions waiting on a verdict.

## Install

Fedora / RHEL, from this repository's dnf repository on GitHub Pages:

```bash
sudo curl -fsSL -o /etc/yum.repos.d/botmaker-dashboard.repo \
  https://botmakerdev.github.io/botmaker-dashboard/botmaker-dashboard.repo
sudo dnf install botmaker-dashboard
```

Later versions arrive with `sudo dnf upgrade`. Debian / Ubuntu: the
[repository page](https://botmakerdev.github.io/botmaker-dashboard/) prints the apt line. x86-64 only; each
version's rpm and deb are also on the Releases page. The packages are signed with the key `botmaker-cli`'s
repository publishes.

## From a checkout

```bash
# from the umbrella root
mvn -pl botmaker-dashboard -am install     # or just: mvn install
mvn -pl botmaker-dashboard javafx:run
```

It asks for the **umbrella checkout** on first run and remembers it. Everything it shows about releases is
read from there — `.gitmodules`, each submodule's git, the committed `releases/*.md` logs — which is why this
is a desktop window and not a page.

## Six tabs

| Tab | What it answers |
|---|---|
| **Modules** | Each module's latest tag, whether HEAD has moved past it, whether that movement is release-relevant, its pom version, and where its latest tag's pins sit. |
| **Releases** | The committed `releases/*.md` logs, newest first, with a re-poll that re-reads JitPack and Actions. |
| **Release** | **Preview** decides a set of flags: the version per module, what is skipped or forced, the tag order, the gates. **Execute…** cuts it, and **Stop** halts it before its next step. |
| **Changelog** | Writes a module's `## [Unreleased]` section — the one the changelog gate refuses a release without — and commits that one file in the submodule. *Draft with Claude* fills the editor. |
| **Queue** | Open pull requests on `botmaker-catalog`, the one file each changes with its fields, and the check's own verdict. Read-only: the catalog merges what passes. |
| **Catalog** | What is published: a grid of bot, plugin and author cards from `catalog.json`, with kind and rank chips and a search. An admin lists another tag, removes an entry or changes an author's rank from a card's panel, each as one pull request. |

## Two rules it is built on

**It never reimplements a decision the release library owns.** The decide pass, the bump arithmetic, the
forcing rules, the tag order and the gates have one implementation, `com.botmaker.cli.release` in
`botmaker-cli`, the same one `release.sh` and `botmaker release` run. The preview calls it in the window's
own process. An executed release runs it in a process of its own, so it survives the window being closed;
the window reads its progress and can be reopened while it runs. A second implementation would diverge on
the first rule added and be discovered by a bad tag, which cannot be edited.

**Admin is the catalog check's admin**: the signed-in account's id among `admin` in `publishers.json` on the
catalog's `main`. There is no second list here: the catalog's check decides who may change what, and this
window only hides edits that check would refuse — it never grants a power.

## Not a library

No JitPack build and no flatten: it is an application, and nothing resolves it as a dependency. It is
tagged by the umbrella release (`--dashboard`, which a `--cli` release forces), which builds
the rpm and deb, the GitHub Release and the dnf/apt repository above.
