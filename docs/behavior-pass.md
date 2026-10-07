# Focused behavior and synchronization pass

This pass builds on `f2e7aff` (package organization), keeping the existing panel,
adapter and plugin structure. It reuses the previously tested wire-layer improvements
without importing the earlier chat model or session/window controllers.

## Ownership and behavior

- **Client:** one session per instance; registration gates commands and desired JOINs.
  Desired channels/keys are separate from server-confirmed membership. Cancelling a
  pending JOIN removes it from intent and the output queue; an in-flight JOIN is followed
  by PART. Reload retains the accepted nick unless the configured nick changes.
- **Transport:** cancellable connect/TLS, registration/read deadlines, bounded priority
  and paced command queues, and asynchronous writes. QUIT is best effort with bounded
  teardown. Session workers are daemon threads and shut down on failure/close.
- **Protocol:** server-confirmed nick changes, bounded initial collision retries, useful
  numeric errors, server CASEMAPPING/NICKLEN, and NAMES transactions that replay intervening
  joins, parts, quits, nick changes and modes. Parsing has bounded input and local tags.
- **Adapter:** one EDT delivery gate rejects retired-session callbacks. Explicitly closed
  channels reject late messages/rosters until rejoined. Local echo follows successful
  socket flush; it is not a server delivery acknowledgement. LIST's timeout starts on
  flush; roster refreshes coalesce while waiting for the EDT.
- **Plugin/UI:** reload waits for old transport closure and rejects duplicate requests.
  Join/Leave buttons and their slash commands update desired channel state during that
  wait, including keys and cancellations; the replacement applies the final state without
  another click. Other network commands/chat still report that they were not sent during
  reload; there is no chat replay queue. Overlay position changes reuse the existing
  instance and keyboard listener. Registration timeout and welcome processing share one lock.
  Plugin commands, panel updates and teardown use the EDT; game chat publication uses
  RuneLite's client thread. A small immutable buffer snapshot serves the overlay.
  Message bursts schedule one HTML render per EDT turn. Sidebar/popout keep sharing the
  existing components.

The plugin's duplicate channel-password map, adapter nickname copy, generic pending
callback queue and duplicate channel-removal path have been removed. Three small
protocol helpers (`IrcLine`, `IrcOutput`, `IrcNames`) provide parsing, queueing and shared
name rules; this is not a general-purpose framework or a third-party IRC dependency.

## Validation

Run with JDK 11: `gradlew.bat test --no-daemon`.

37 tests cover loopback socket registration/cancellation/closure and echo, nick errors,
roster synchronization, decoding, output bounds/priority, reload intent and nick retention,
late events after close/retirement, immutable overlay snapshots, render batching and
native window rehosting, timeout/welcome ordering, and overlay listener cleanup. The suite
also compares 70 input lines against five saved
protocol transcripts (250 lines) captured from the earlier hardened implementation at
`dff9c44`. Those fixtures protect that established behavior; they are not a claim of
exact equivalence with this branch's pre-fix bugs. No public IRC network is used.

## Remaining scope

This does not import every previous feature/fix. Per-conversation drafts, merging
colliding PM buffers and the RuneLite settings-checkbox refresh workaround are unchanged.
The UI still uses case-insensitive display-name matching in places, while protocol
membership uses the server's full case mapping. Stalled writes are interrupted on
disconnect; a separate stalled-write watchdog and abandoned NAMES cleanup are not added.
Live RuneLite/network testing remains useful in addition to the automated checks.
