# Tiers and vetting — the gallery decides, this window proposes (2026-09-16)

**A bot is Vetted exactly when `vetted/<owner>-<repo>.json` exists on the gallery's `main`**, and Community
otherwise. That is `GalleryCatalog`'s rule, in `botmaker-cli`, and the Tier column reads the same files rather
than the generated `catalog.json`, for the reason the Catalog reads entry files. `Catalog.attach` matches on
`owner/repo` without case because `GalleryCatalog` does; a column that disagreed with the catalog Studio reads
would be the second implementation this module exists not to have.

**`Vetting.vet` and `Vetting.revoke` are pull requests, like every write here**, and they check nothing. The
gallery's gate runs over a maintainer's `vetted/` change and refuses a release that does not download or a
bot that is not listed; its merge job sends every change outside `bots/` to a maintainer, so the proposal
waits in the Queue tab and is merged there. The record is `VettedRecord` through `Registry.mapper()`, which
is why a dashboard vetting is byte-identical to one written by hand.

**The Queue's Auto-merge column is the merge job's labels, read and never recomputed.** `ListingPolicy` in
`botmaker-cli` decides; `automerge.yml` writes `waiting` or `needs-maintainer` and one comment starting
`<!-- botmaker-listing -->`. `Submission.autoMerge()` reads the labels; the comment is fetched for the
selected row only.
