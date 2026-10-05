# The rule the whole module hangs on

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
