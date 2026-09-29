# Source packages

The package layout groups the existing implementation by responsibility. Start with
`com.irc.IrcPlugin` for RuneLite wiring and command handling.

| Package | Contents |
| --- | --- |
| `com.irc` | Plugin entry point and configuration |
| `com.irc.protocol` | Custom IRC client, bounded decoder/output queue, server rules, channel rosters, LIST entries and formatting codes |
| `com.irc.session` | Adapter between protocol events and plugin/UI behavior |
| `com.irc.model` | IRC message value and message types |
| `com.irc.ui` | Sidebar, desktop layout, popout host, channel browser, input history and previews |
| `com.irc.overlay` | In-game overlay and keyboard integration |
| `com.irc.emoji` | Existing emoji parsing and data support |

The panel owns conversation UI state; the client owns wire/session state. The adapter
delivers events on the EDT and drops callbacks once retired. The game overlay reads
a small immutable buffer snapshot. No separate chat model or session controller is
needed for these responsibilities. See [behavior and validation](behavior-pass.md).

The plugin entry point remains `com.irc.IrcPlugin`. Panel icons retain their existing
resource locations under `/com/irc/`, now referenced by absolute classpath paths.
Protocol parsing and roster mutation helpers remain package-private; only existing
cross-package data and operations receive public access.

Protocol parsing and output helpers have package-local tests; integration tests cover
the adapter, reload path, and native Swing windows. The RuneLite launch harness remains
available for manual testing.
