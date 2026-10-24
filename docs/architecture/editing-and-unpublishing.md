# An admin's edits — pull requests the catalog merges

> **2026-10-23.** *Edit…* (free-text JSON) and *Unpublish…* went with the human review. The edits are the three the
> user named: list another tag, remove an entry, change an author's rank.
>
> **2026-10-24.** An entry keeps every approved release (catalog schema 4), so *List this tag* became **Remove**
> beside each release: a publish appends a release, and an admin only takes one out.

**`CatalogEdits` opens one pull request per edit and changes nothing else.** The catalog's `validate` check runs
over it like any other, with the admin exception (`EntryGate --author-id`), and `automerge.yml` merges it. The
dashboard never merges, so the catalog's history stays a list of pull requests saying who changed what.

- **Remove** beside a release (`CatalogEdits.removeRelease`) writes the entry file without that release, the
  others and every other field kept in their order: the check's `Remove` change. It asks first, saying Studio stops
  offering the release and refuses it where it was loaded. An entry's only release has no Remove: it goes with
  the entry. A file on `main` that no longer lists the tag, or lists nothing else, opens nothing.
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
