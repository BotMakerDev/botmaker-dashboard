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
version's rpm and deb are also on the Releases page.

**The next release signs itself.** The three signing secrets are set on the `BotMakerDev` organization,
which this repository joined on 2026-09-18, so `GPG_KEY_ID` is no longer empty here. v0.0.3 was published
unsigned — with `gpgcheck` off in its `.repo` and the repository page saying so, rather than implying a
check nobody performed. The key is the one `botmaker-cli`'s repository publishes; the umbrella's
`tools/signing-secret.sh` is how it got there.

## From a checkout

```bash
# from the umbrella root
mvn -pl botmaker-dashboard -am install     # or just: mvn install
mvn -pl botmaker-dashboard javafx:run
```

It asks for the **umbrella checkout** on first run and remembers it. Everything it shows about releases is
read from there — `.gitmodules`, each submodule's git, the committed `releases/*.md` logs, and
`./release.sh` itself — which is why this is a desktop window and not a page.

## Four tabs

| Tab | What it answers |
|---|---|
| **Modules** | Each module's latest tag, whether HEAD has moved past it, whether that movement is release-relevant, and where its `.deps.env` pins sit. |
| **Releases** | The committed `releases/*.md` logs, newest first, with a re-poll that re-reads JitPack and Actions. |
| **Release** | What `./release.sh --dry-run` decides for a set of flags: the version per module, what is skipped or forced, the tag order, the gates. |
| **Queue** | Open pull requests on `botmaker-plugin-registry` and `botmaker-gallery`, the one entry file each adds, and the gate's own verdict. |

## Two rules it is built on

**It never reimplements a decision `release.sh` owns.** The decide pass, the bump arithmetic, the forcing
rules, the tag order and the gates have exactly one implementation. This app shells to it and reads its
output. A second implementation would diverge on the first rule added and be discovered by a bad tag, which
cannot be edited.

**Admin is `permissions.push` on the plugin registry, read from the GitHub API.** There is no allowlist and
no role table: the power already exists on github.com, and a second list of who holds it is a list that goes
wrong. This window reveals a power GitHub enforces regardless — it never grants one.

## Not a library

No JitPack build and no flatten: it is an application, and nothing resolves it as a dependency. It is
tagged by the umbrella release (`--dashboard`), which builds the rpm and deb, the GitHub Release and the
dnf/apt repository above.
