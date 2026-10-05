# Why some things are duplicated from Studio, and one thing is not

**`AccountBar` is a second implementation of the *control*, never of the *flow*.** The device-flow
handshake, the poll, the token file and its `0600` are `com.botmaker.shared.github.GitHubAuth` — that
package left Studio on 2026-09-05 for this module. What is written here is four buttons and an alert,
because Studio's bar is themed by Studio's `BlockTheme`, hangs off Studio's dialogs and answers to Studio's
window. A widget with two owners is how this module would acquire a dependency on an application.

**The stylesheet is likewise its own.** Copying four colours is cheaper than depending on an app, and
Studio's tokens are named for a block canvas this window does not have.

**What must never be copied is a decision.** A release rule, a gate, a version bump, a verdict on a
submission — those have one owner each (`release.sh` today, `com.botmaker.cli.release` after Part C, and
`RegistryGate` for the gate). The line to hold: *duplicate presentation freely, never logic that can be
wrong.*
