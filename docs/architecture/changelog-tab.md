# The changelog tab — one section, one commit, no push

`ChangelogGate` refuses a module whose `CHANGELOG.md` describes neither the version being cut nor an
`## [Unreleased]` release, and `Stamp` renames that heading in the release commit. So the section is the one
piece of a release a human has to write, and until 2026-09-16 the only way to write it was to leave this
window. **`umbrella/ChangelogEdit` splices that section by offset** — the heading, the body up to the next
`## ` heading, and nothing else — for `Stamp`'s own reason: a whole-file rewrite normalises line endings and
the final newline in a file the maintainer has been editing all week, and the commit then carries that
reformatting as if it were the edit. A missing section is **inserted** above the newest stamped one rather
than refused, since leaving that state behind is what the tab is for.

**Save commits one file inside the submodule and pushes nothing.** It refuses a `CHANGELOG.md` that already
had uncommitted changes when the tab read it: this commit's message is about the notes, and an edit somebody
had in flight would ride along under it. A push is the release's business — the pointer commit is where that
gets decided.

**Draft with Claude fills the editor and saves nothing.** It is the one place here that spends somebody's
account, so two conditions hold before it is even visible: `claude` and `cswap` on `PATH`, and the signed-in
GitHub login equal to the repository owner. **Hidden, not disabled** — a disabled button is a promise this
window cannot keep for another account. The prompt carries facts only (the module's own changelog preamble,
its newest stamped section as the house style, the commits since its tag and a `git diff --stat`) and asks for
Keep-a-Changelog bullets and nothing else; `--allowedTools ""`, because the draft is writing about text
already in the prompt and a model reading this working copy is not what was asked for.

**An account is a slot and never an address.** `CswapAccounts` parses `cswap list` and keeps the number and
the five-hour usage, deliberately dropping the email — the status line says `account 1 (5h: 9%)`. The
least-used account is tried first and the rest in order after it, so a rate limit moves the work along.
`claude` **exits 0 on a usage limit** and prints the sentence, which is why the output is checked for one:
the exit code alone would write that sentence into the changelog as release notes.

**Draft all is the one exception to "fills the editor and saves nothing", and it earns it** (2026-09-17).
`umbrella/ChangelogDrafts` writes and **commits** a section for every module that has none, because its
purpose is to lift `ChangelogGate` for a whole constellation and a draft in a text area lifts nothing. Two
rules, decided by the commits since the module's newest tag: **none** means the module is being re-released
for its pins only, so the section is one line saying so plus the previous section's body carried forward —
no model, because a model would be asked to invent; **some** means Claude drafts from them. The drafter is a
parameter (`ChangelogDrafts.Drafter`), so the copy-or-draft rule is tested over a real repository with a
lambda. Each section goes through `ChangelogEdit.save` — one file, one commit, nothing pushed — and a dirty
`CHANGELOG.md` is a failure for that module, never a commit that carries somebody's edit. The button asks
first and names the modules; it is visible to the maintainer even without Claude, since the copies still
happen and the report says what was left.

**The Release tab's Preview calls the same thing first.** `Backend.autoDraft` runs `ChangelogDrafts` over
the modules the flags would cut (`ReleaseSpec.requested()`, minus the exempt ones) before `ReleaseRun.go`,
because the decide pass reads the *committed* changelog. A module it could not write is a refusal of the
preview by name — `preview refused: … need a changelog and none could be written` — and Execute stays dead.
The default `autoDraft` does nothing, which is what a test backend over a fixture wants.
