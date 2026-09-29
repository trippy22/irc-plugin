# Source packages

The package layout groups the existing implementation by responsibility. Start with
`com.irc.IrcPlugin` for RuneLite wiring and command handling.

| Package | Contents |
| --- | --- |
| `com.irc` | Plugin entry point and configuration |
| `com.irc.protocol` | Custom IRC client, server mode rules, channel rosters, LIST entries and formatting codes |
| `com.irc.session` | Adapter between protocol events and plugin/UI behavior |
| `com.irc.model` | IRC message value and message types |
| `com.irc.ui` | Sidebar, desktop layout, popout host, channel browser, input history and previews |
| `com.irc.overlay` | In-game overlay and keyboard integration |
| `com.irc.emoji` | Existing emoji parsing and data support |

This is a package-only reorganization of the current branch. Connection lifecycle,
protocol handling and UI behavior retain their existing implementations. No shared
chat model, session controller, or fixes from other branches are introduced.

The plugin entry point remains `com.irc.IrcPlugin`. Panel icons retain their existing
resource locations under `/com/irc/`, now referenced by absolute classpath paths.
Protocol parsing and roster mutation helpers remain package-private; only existing
cross-package data and operations receive public access.

Validation: `gradlew.bat clean test jar --no-daemon` succeeded with JDK 11. The current
test source is a RuneLite launch harness, not an automated regression suite. A source
comparison against this branch's starting commit verified that production edits were
limited to packages, imports, access modifiers, icon paths and one Javadoc reference.
