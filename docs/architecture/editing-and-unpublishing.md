# An admin's edits — pull requests the catalog merges

> **2026-10-23.** *Edit…* (free-text JSON) and *Unpublish…* went with the human review. The edits are the three the
> user named: list another tag, remove an entry, change an author's rank.

**`CatalogEdits` opens one pull request per edit and changes nothing else.** The catalog's `validate` check runs
over it like any other, with the admin exception (`EntryGate --author-id`), and `automerge.yml` merges it. The
dashboard never merges, so the catalog's history stays a list of pull requests saying who changed what.

- **List this tag** writes the entry file with the new `tag` and every other field kept in its order (`id`, `repo`,
  `detached`). A blank tag or the tag already listed opens nothing. The check still builds the release: an admin
  can list only a published tag that passes.
- **Remove from catalog…** deletes the entry file, after a confirmation (`Themed.dialog`) saying Studio stops
  listing it once merged and the repository stays.
- **Rank** + **Save rank** moves the account by **id** in `publishers.json`, read again from `main` first. An
  author with no id yet is asked of GitHub (`GET /users/<login>`). Community takes the account off both lists. A
  move that changes nothing, or would leave no admin, is refused before any request.

**From a branch or a fork, Studio's `CatalogListing` shape.** An account that can push to the catalog branches
there; any other forks first. The branch is `dashboard/<verb>-<key>-<UTC stamp>`: without the stamp a second edit
while the first is open is a 422 naming an existing ref.

**The blob `sha` read on `main` is sent back with the write.** GitHub refuses it if the file moved since —
optimistic locking: two admins editing one entry is exactly the case one-file-per-entry makes visible.

**The grid is not reloaded after a proposal**, and that is the honest thing: nothing listed changes until the
check passes, the merge job merges and the index job regenerates `catalog.json`. The status line and the panel's
foot name the pull request opened; it is in the Queue tab meanwhile.
