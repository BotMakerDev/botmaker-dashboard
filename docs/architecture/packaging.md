# Released as a Linux package, and what that still omits on purpose (2026-09-17)

**An installable app, Studio's shape, Linux only.** `Module.DASHBOARD` is the last flag in the release
(`--dashboard`), tagged last: on a `v*` tag `ci.yml`'s `package` job checks out the four upstreams at the
refs in `.deps.env`, installs them at `0.0.0-SNAPSHOT`, runs `mvn -Pdist package` (shaded jar → jpackage
app-image → rpm + deb) and the `release` job publishes both installers through JReleaser with the changelog
section as body (`tools/changelog-section.sh`, the same extractor the gate reads). No Windows leg, no
AppImage, no dnf/apt repository, no signing.

**`--cli` forces `--dashboard`, and the notice in the top bar is the other half of the same fact.** The
Release tab calls `com.botmaker.cli.release` in-process, so an installed dashboard decides by the cli it was
*built* with. The release never cuts a cli without a dashboard; a checkout can still be *ahead* of the
installed build, and `umbrella/BuiltWith` says so: the `dist` profile bakes `.deps.env` into the jar as
`META-INF/botmaker/.deps.env`, and `UmbrellaBar` compares its `CLI_TAG` with `git describe --tags` in the
checkout's `botmaker-cli` — *built with cli vX, checkout at vY — preview may follow older rules*. A
development run has no baked file and shows nothing: there the cli on the classpath *is* the checkout's.

**`.deps.env` pins four**: shared, the cli, and the cli's own two pins (contract, loader), because
installing the cli from source resolves them. The first file, written by hand before the first release, pins
`main` for the two unreleased ones; the release overwrites it with exact refs.

**Still no JitPack, still no flatten.** Nothing resolves this module as a dependency, so there is no
published pom for a `-D` to be missing from. `pom.xml` says so where the flatten would go.
