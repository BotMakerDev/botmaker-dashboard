# Editing and unpublishing — pull requests, never `main`

**`Catalog.edit` and `Catalog.unpublish` open a pull request and change nothing else.** Branch `main`,
`PUT` or `DELETE` the entry file on that branch, open the pull request. Merging is somebody's decision,
made where every other submission is decided.

**Why not a push to `main`.** The entry file is the source of truth and `index.json` is generated from it by
CI, so a commit straight to `main` leaves an index that disagrees with the entries until the next job runs —
which is the same hazard as a hand-edited `index.json`, from the other end. And a pull request runs
`RegistryGate` over the *result*, which is the only way an edit gets the same check a submission gets.

**No fork, and that is the difference from `PluginPublishCommand`.** That command forks because a submitter
usually cannot push to the registry; an operator with `permissions.push` can, so the branch goes straight
there. Reusing that command's flow would mean shelling to `gh` from a GUI and forking the maintainer's own
repository, which GitHub refuses anyway.

**The blob `sha` from the listing is sent back with the write.** GitHub then refuses it if the file moved
since it was read — optimistic locking, not a courtesy: two operators editing one entry is exactly the case
one-file-per-entry exists to make visible.

**The branch name carries a UTC timestamp**, and the id is reduced to the characters a git ref may hold.
Without the timestamp a second edit while the first pull request is open is a 422 naming an existing ref,
which reads as a bug in this window; without the reduction, a bot's `owner/repo` id would put a second
segment in the branch name.

**Two things the dialogs do that are not gates.** The edit dialog says whether the text parses and does
**not** refuse it — whether an entry is good is `RegistryGate`'s answer, and a syntax opinion here is the
first step towards a second gate. An edit that changed nothing opens no pull request, which is arithmetic
rather than judgement. Unpublish asks the operator to **type the id**, because merging it removes the entry
for everyone and the id is the one thing re-submitting cannot recover — the filename is the claim.

**It is a text area over the JSON, not a form.** A form shows only the keys it was written to know about,
so it would silently drop one an entry carries that this window has never heard of. Same rule as
`EntryFields`.

**The catalog is not reloaded after a proposal**, and that is the honest thing: nothing published has
changed. The proposal is in the Queue tab now, with the gate's verdict against it.
