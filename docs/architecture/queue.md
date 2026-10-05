# The queue — one judgement, and it is about shape

A submission is a pull request adding **one file**: `plugins/<plugin-id>.json`,
`bots/<owner>-<repo>.json` or — a maintainer's vetting — `vetted/<owner>-<repo>.json`. Everything about whether it is *good* belongs to the registry's CI, which runs
`RegistryGate` from `botmaker-cli`'s main artifact — the same code its author ran as
`botmaker plugin validate`. The Gate column is that check run's conclusion, in GitHub's own words.

**The one thing judged here is that shape**, because it is a property of the layout rather than of a plugin:
one file per entry is what makes two same-day submissions two files that cannot conflict, what makes git
itself refuse a second claim on an id, and what lets `index.json` be generated rather than edited. So a pull
request touching anything besides its own entry is flagged loudly and **Merge is refused locally** —
`index.json` by hand is the common case, and it is stale the moment another submission merges. Two entry
files is *no* entry file: which id is being claimed has no answer, so there is nothing safe to merge.

**`Checks.NONE` is where this deliberately differs from `ReleaseLog.Health`.** The release log reads
`no run on <tag>` as **broken**, because a tag is finished and nothing more will fire. A pull request is not
finished — one opened a minute ago simply has no check run yet. Same JSON, opposite verdict, because the
subject's lifecycle differs; a shared classifier here would have to be wrong for one of the two.

**`EntryFields` reads the file, never a schema.** What an entry must contain has one owner and it is the
gate. A copy of it here would go stale on the first field added and, worse, would *hide* a key a submission
carries that this window has never heard of — which is exactly the key a reviewer needs to see.
