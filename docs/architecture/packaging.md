# Released as a Linux package, and what that still omits on purpose (2026-09-17)

**An installable app, Studio's shape, Linux only.** `Module.DASHBOARD` is the last flag in the release
(`--dashboard`), tagged last: on a `v*` tag `ci.yml`'s `package` job resolves the upstreams the tag's pom
pins from JitPack, runs `mvn -Pdist package` (shaded jar → jpackage app-image → rpm + deb) and the `release`
job publishes both installers through JReleaser with the changelog section as body
(`tools/changelog-section.sh`, the same extractor the gate reads). No Windows leg, no AppImage, no signing
beyond the rpm's.

**Real pins since 2026-10-06** (umbrella `docs/refactor/43-real-versions.md`). The pom pins shared and the
cli: on `main` each upstream's `-SNAPSHOT`, which `build` installs from their main branches; on a tag the
released versions the release commit set, which `package` resolves from JitPack, and the release waits for
those JitPack builds first (`Waits`, `Module.resolvesFromJitpack`). A dispatch from a branch has snapshot
pins, so it installs the four from main as `build` does. Until then a `.deps.env` named the refs and
`package` checked the four out from source at `0.0.0-SNAPSHOT`.

**`--cli` forces `--dashboard`, and the notice in the top bar is the other half of the same fact.** The
Release tab calls `com.botmaker.cli.release` in-process, so an installed dashboard decides by the cli it was
*built* with. The release never cuts a cli without a dashboard; a checkout can still be *ahead* of the
installed build, and `umbrella/BuiltWith` says so: the `dist` profile filters the pom's
`botmaker.cli.version` into the jar as `META-INF/botmaker/built-with.properties` (`src/dist/resources`), and
`UmbrellaBar` compares it, spelled as a tag, with `git describe --tags` in the checkout's `botmaker-cli` —
*built with cli vX, checkout at vY — preview may follow older rules*. A development run has no baked file and
shows nothing: there the cli on the classpath *is* the checkout's.

**Still no JitPack build of this module, still no flatten.** Nothing resolves it as a dependency, so there is
no published pom for a `-D` to be missing from. `pom.xml` says so where the flatten would go.
