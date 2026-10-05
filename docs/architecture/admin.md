# Admin — GitHub answers it, and there is no second list

`Admin.probe` reads `permissions.push` from `GET /repos/LiQiyeDev/botmaker-plugin-registry` for the
signed-in account. That bit enables the write actions. Nothing else is consulted.

An allowlist in this repository was considered and refused. It would be a second statement of a fact that
lives on github.com, in a place with no way to notice the first one changing — someone removed from the
project keeps the buttons, someone added waits for a release. And it could not be made good on: approving a
pull request needs the push right *at the moment of the call*, so an app trusting its own list produces a
403 in a dialog instead of a disabled button.

**So a refusal here is not a security boundary and must never be written as one.** The API still decides.
What the verdict buys is that the operator learns they cannot merge before writing a review, not after.

Every probe failure — offline, not signed in, a narrowed token scope, a repository the account cannot see —
is **read-only with a reason**, never an error dialog. The window stays fully usable for everything it
reads, which is most of it.
