# The catalog — one card grid of what is listed

> **2026-10-23.** The registry and the gallery are one repository, `botmaker-catalog`, for bots and plugins. The
> Catalog tab is a card grid (the user's pick, layout A). Spec: the umbrella's
> `docs/superpowers/specs/2026-10-23-dashboard-catalog-design.md`.

**The Queue answers *what is in flight*; the Catalog answers *what can somebody install*.** It reads
`catalog.json` on the catalog's `main` through the raw URL (`GitHubConfig.catalogRawUrls()`), the same file Studio
reads, so it shows exactly what Studio lists. `catalog.json` is generated from the entry files by the catalog's
`index.yml`; reading the entry files instead would show entries Studio does not list yet.

**It reads github.com, not the checked-out submodule.** The umbrella's pointer trails `main` every time the index
job commits, and a stale catalog is indistinguishable from a current one.

**Rows are the dashboard's own records** (`CatalogRow`), read as data: the cli's `Rank` is reused, nothing else of
the builder. A row with no `repo`, no `tag`, or a plugin with no `id` is dropped and the rest still show; an
unknown rank reads as Community, as Studio reads it; a file that does not parse is an empty grid and one sentence.

**Authors are cards too** (`Authors`): every row's owner and every account `publishers.json` ranks, so an admin
can rank somebody who has listed nothing yet. Logins compare case-insensitively. The rank is `publishers.json`'s
when it lists the author, otherwise the highest the catalog gave their rows.

**Which cards show is `CatalogFilter`'s**, a pure function: one kind chip (All, Bots, Plugins, Authors), any rank
chips (none = all), and search words that must each appear in a card's name, owner or tags. The tab draws its
answer and decides nothing (`ui/CatalogCards`). An owner link on a card switches to Authors and selects that
author, clearing the chips and words only when they would hide it.

**The side panel** shows the selected card: for a bot or plugin its repository and tag, stars, "based on …", tags,
a bot's plugins at its tag, the entry path, and links (GitHub, JitPack for a plugin, the entry file); for an author
their account id, their bots and plugins (each a link to its card) and their GitHub page. An admin also gets the
edits (`editing-and-unpublishing.md`).
