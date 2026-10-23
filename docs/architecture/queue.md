# The queue — read-only, and one judgement about shape

> **2026-10-23.** The catalog merges itself, so Approve, Request changes and Merge went, and with them
> `EntryFields`. Spec: the umbrella's `docs/superpowers/specs/2026-10-23-dashboard-catalog-design.md`.

The Queue tab lists the catalog's open pull requests. **Nobody reviews them.** Each runs the catalog's `validate`
check (`EntryGate`, from `botmaker-cli`'s main artifact), and `automerge.yml` merges what passed, or labels it
`waiting` for the author's daily limit. So a row says what is in flight and why it has not merged yet: the check's
conclusion in GitHub's own words, the `waiting` label, and the one file it changes. Anything else is said on the
pull request on GitHub, which *Open on GitHub* opens.

**The one thing judged here is shape**, because it is a property of the layout rather than of an entry: one path
per pull request — `bots/<owner>__<repo>.json`, `plugins/<id>.json`, or `publishers.json` — is what makes two
same-day submissions two files that cannot conflict. A pull request touching anything else is flagged in red. The
check refuses it anyway; the flag says so before the check has run.

**`Checks.NONE` is where this deliberately differs from `ReleaseLog.Health`.** The release log reads
`no run on <tag>` as **broken**, because a tag is finished and nothing more will fire. A pull request is not
finished — one opened a minute ago simply has no check run yet. Same JSON, opposite verdict, because the
subject's lifecycle differs; a shared classifier here would have to be wrong for one of the two.

**The detail reads the file, never a schema.** `Queue.fields` lists the file's top-level fields at the pull
request's head, in its order (an account list by its logins), and the raw text sits below. What an entry must hold
is the check's; a field this window has never heard of still shows.
