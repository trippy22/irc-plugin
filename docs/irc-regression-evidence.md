# IRC cleanup regression evidence

Verified locally on 2026-09-28 with Windows, Zulu JDK 11.0.29, and the Gradle wrapper.
The comparison baseline is commit `dff9c44` (`small irc rework`), which already contains the
shared model/session/window architecture. This measures the cleanup against that commit;
it does not retroactively prove equivalence of the earlier architectural rewrite.

## Before and after

Before changing production code, `IrcProtocolCompatibilityTest` replayed five scripted
exchanges against the committed implementation and captured fixtures under
`src/test/resources/irc/`. The temporary recording branch was then removed from the test;
normal test execution can only compare, never regenerate those expectations.

The committed implementation plus the new comparisons passed **42 tests**, with zero
failures, errors, or skips. After cleanup, **41 tests** passed with zero failures, errors,
or skips. The removed test exercised only the deleted, unused generic callback API.
Its relevant registration guarantee is now checked in the actual socket test: after a
duplicate `001` welcome, no duplicate JOIN is sent during an interval longer than the
normal command pacing interval.

All **70 incoming IRC lines / 250 transcript lines** matched the baseline exactly:

| Fixture | Input lines | Compared behavior |
| --- | ---: | --- |
| `cap-sasl.txt` | 8 | Multiline CAP LS, requested capabilities, SASL PLAIN's exact 400-byte base64 boundary and terminator, CAP END, registration, PONG, history request |
| `cap-rejected.txt` | 7 | SASL failure, CAP NAK, registration and join without history |
| `messages-history.txt` | 14 | Channel/private messages, ACTION, VERSION/PING replies, user/server notices, batched message timestamps, subsequent live message |
| `rosters.txt` | 17 | PREFIX/CHANMODES/CASEMAPPING, chunked NAMES with intervening PART/JOIN/NICK/QUIT/MODE/KICK, accepted/rejected nicknames, self kick |
| `numerics.txt` | 24 | Topics, WHOIS, LIST results/throttling, bad key/missing/full/invite-only/banned channel errors, malformed replies |

Each input compares emitted events, command requests, accepted nickname, registration,
confirmed membership, sorted rosters, and completed LIST rows. History events compare
their nested messages and timestamps. These fixtures intercept command submission to
make the protocol comparison deterministic; they do not simulate socket delivery.
Their credentials and messages are synthetic.

## Independent behavioral checks

| Area | Tests | Evidence |
| --- | ---: | --- |
| Real loopback sockets | 8 | Registration-gated JOINs, cancelled JOINs, retained nick/channel keys, duplicate welcome, disconnect and worker termination, TLS handshake cancellation, EOF, flush-based echo, retired adapter callbacks |
| Protocol state | 10 | Rejected/invalid nicknames, bounded collision retries, desired versus confirmed channels, interleaved NAMES changes, case mapping, malformed replies, fatal registration errors, invalid/offline send rejection |
| Output queues | 3 | Bounds, protocol priority and pacing, failed flush cannot report success |
| Line decoding | 2 | Tags/trailing parameters and bounded input |
| Shared model | 5 | Drafts/unread state, closed-channel tombstones, rename merges, immutable bounded history, EDT ownership |
| Session controller | 2 | Late replies after close and duplicate reload preserving conversation state |
| Native Swing/configuration | 5 | Real RuneLite checkbox sync on Dock/X and one-click reopen, native rehosting/listeners, desktop channel/user interaction, retained drafts and layout switching |
| Model-to-view rendering | 1 | A 100-message burst produces one render with bounded history; clearing and appending cannot resurrect old messages |
| Baseline transcript comparisons | 5 | Exact equality for the five exchanges above |

The render test now exercises `IrcChatModel.append/clear` → immutable snapshot →
`ChannelPane.showMessages`, the same path production uses, instead of the removed
direct append helper. The native UI tests actually ran; none were skipped as headless.

## Reproduce

```powershell
$env:JAVA_HOME='C:/Program Files/Java/zulu11.84.17-ca-jdk11.0.29-win_x64'
./gradlew.bat test --no-daemon
```

Detailed local results: `build/reports/tests/test/index.html` and
`build/test-results/test/TEST-*.xml`. A desktop-layout image rendered by the test is
`build/previews/irc-desktop.png`. `git diff --check` also passed.

## Scope

No third-party IRC library or build dependency was added. Custom protocol handling and
formatting remain in this project; user-provided reference files under `hints/` were untouched.

This establishes no observed regression in the covered cases, not a mathematical proof
for every server or thread schedule. No public IRC network or full logged-in RuneLite
session was exercised during this cleanup. Existing limitations documented in the
architecture notes, including stalled-write timeouts and abandoned NAMES transactions,
remain outside this change.
