# The catalog — what shipped, which the queue cannot answer

**The Queue tab lists open pull requests, and that is usually zero.** It is the truthful answer to *what is
waiting on me* and no answer at all to *what can somebody install*. `Catalog` is the second question: every
**merged** entry — `plugins/<plugin-id>.json` and `bots/<owner>-<repo>.json` — with the gallery entries
carrying `GalleryEntry.TEMPLATE_TAG` shown as templates rather than bots.

**It reads github.com, not the checked-out submodule**, and that is the one decision in it. The umbrella has
both data repositories as submodules, and `ModulesTab`/`ReleasesTab` beside it read the checkout — but they
do so because a release only exists in a working copy. A merged entry exists on `main`; the umbrella's
recorded pointer trails it every time either repository's CI commits a regenerated index (both pointers were
behind on 2026-09-16, which is how this was noticed), and a stale catalog is indistinguishable from a
current one.

**It reads the entry files and not the generated `index.json`**, for the reason the layout exists: one file
per entry is what makes two same-day submissions two files that cannot conflict and what makes git itself
refuse a second claim on an id. The index is derived from them by a job, so reading it shows what the job
last produced rather than what the repository holds.

**The typed halves are the CLI's records** — `RegistryEntry`, `GalleryEntry`, `Registry.ENTRIES_DIRECTORY`,
`Registry.INDEX`, `GalleryEntry.TEMPLATE_TAG` — read through `Registry.mapper()`, which disables
`FAIL_ON_UNKNOWN_PROPERTIES`. Both are pure Jackson records naming no contract type, so they are safe under
this pom's `<exclusions>`. A field added to the entry shape tomorrow therefore lists today, and still reaches
the field view, because `EntryFields` reads the raw text beside it.

**An entry that will not parse is a row, never a dropped one.** It is on `main`, so somebody merged it and
either the gate passed it or never ran; it keeps its filename as its identity, is counted separately in the
status line, and its bytes still reach `EntryFields`. Burying it in a total is how it stays unnoticed.

**`Contents` is the one HTTP shape both readers share.** `Queue` wants the entry a pull request adds, at
that pull request's head; `Catalog` wants the entry that is merged, on `main`. One ref apart — so the
request, the base64 decode and the "reads work signed out" token rule live in one place rather than two.
The writes are there too, for the same reason: one URL builder, so a path that escapes correctly for a read
escapes correctly for the write after it.
