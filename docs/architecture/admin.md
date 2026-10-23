# Admin — the catalog check's own list, and no second one

> **2026-10-23.** Admin was GitHub's `permissions.push` on the plugin registry until the catalog merged itself. It
> is now the check's admin.

`Admin.probe` asks `GET /user` for the signed-in account and reads `publishers.json` on the catalog's `main`
(`PublishersFile`). The account is an admin when its **id** is in that file's `admin` list. That enables the edits
in the Catalog tab's side panel. Nothing else is consulted.

**Why that file.** The catalog's `EntryGate` lets an admin change anyone's entry and the ranks, and it reads
exactly that file by exactly that id. Reading the same file means the dashboard offers the edits to the accounts
the check will pass, and to no others. Push access no longer means anything here: nothing in this window merges.

**By id, never by login.** A login can be renamed and then taken by somebody else; the id cannot. A login that
matches with a different id is not an admin (`AdminTest`).

**A refusal here is not a security boundary and must never be written as one.** The check decides; an edit by
anyone else fails there with its reason. What the verdict buys is that a non-admin is not offered buttons whose
pull requests would be refused.

Every probe failure — offline, not signed in, a token that expired, a `publishers.json` that does not parse — is
**read-only with a reason**, never an error dialog. The window stays fully usable for everything it reads, which
is most of it.
