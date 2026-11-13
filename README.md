# botmaker-dashboard (archived)

**This repository is archived.** Its release window is now **sluice-desktop**, the window of
[sluice](https://github.com/BotMakerDev/sluice), the release tool that releases BotMaker since 2026-11-13. It shows
each module's state, previews a release, runs it and reads the release logs, for any umbrella with a `sluice.yml`.
The dashboard's queue and catalog tabs were not carried over.

Install it with the command, from sluice's signed dnf and apt repositories:

```bash
curl -fsSL https://botmakerdev.github.io/sluice/install.sh | sudo bash -s -- --desktop
```

Then remove the dashboard and its repository:

```bash
sudo dnf remove botmaker-dashboard && sudo rm -f /etc/yum.repos.d/botmaker-dashboard.repo   # Fedora / RHEL
sudo apt remove botmaker-dashboard                                                         # Debian / Ubuntu
```

From the BotMaker umbrella, `./release.sh` runs the same release from a terminal.

The code stays here, read-only, at its last commit; its history is in `CHANGELOG.md`.
