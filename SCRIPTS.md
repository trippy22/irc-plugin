# IRC scripting

The plugin includes a small Java interpreter and a Swing editor. This is an explicitly limited
MSL-inspired language, **not a complete mIRC compatibility layer**. No new dependencies are used.
Implementation lives in `com.irc.scripting`; `IrcScripts` and immutable `ScriptContext` are the
plugin-facing API. The parser, runtime, storage, permissions, and editor are package-private.
Security and spam prevention take precedence over compatibility.

## Opening and editing scripts

Click **S** in the IRC toolbar or enter `/scripts` in the IRC input. The same manager serves
the docked panel and the popout. In game chat, the existing command prefix also works
(for example, `;;scripts` with the default prefix).

- **New** creates a managed `.mrc` file with an example. **Add file** opens RuneLite's file
  chooser and copies the selected text file into managed storage. The original is untouched.
- A row opens its editor. Its **Enabled** checkbox controls execution and startup loading.
  New/imported scripts are disabled until explicitly enabled. Enabling loads the saved file,
  not unsaved editor contents.
- **Allow replies to the originating conversation** permits `msg`/`notice` only back to the
  invoking channel or query. **Allow private conversations** permits private TEXT events and,
  together with reply permission, private replies. Both permissions default off. They are separate
  from Enabled and cannot be granted by a script. Changing either permission cancels existing timers.
- The editor provides line numbers, highlighting, literal find/replace, undo/redo, indentation,
  and automatic indentation on Enter. Ctrl+F searches, Ctrl+Z/Ctrl+Y undo/redo, Tab/Shift+Tab
  indent/outdent, Ctrl+S saves, and Ctrl+Shift+S saves and reloads.
- **Save** writes the file without changing the active version. **Save & Reload** first validates
  the new source. A validation error leaves both the old file and active program intact.
  **Reload disk** validates the saved file; disabled scripts stay disabled.
- Errors appear in the script's Status column. Editor operation errors appear below the editor;
  a reported line number moves the caret to that line. Some value-dependent errors can only
  be detected when a script runs. Reload does not promise that all possible executions succeed.
- Switching files or closing with unsaved changes offers Save/Discard/Cancel. On plugin shutdown,
  unsaved editor contents are queued for recovery as a separate draft; they do not become executable.
  Abrupt process termination or disk failure can prevent that recovery write.
- **Remove** unloads the script and removes its registration, retaining its managed file and state.
  **Delete file** additionally deletes the managed file, saved variables, and recovery draft after
  confirmation. It never deletes an original imported file.
- If an external editor changes the file, saving from the plugin refuses to overwrite it. Copy any
  unsaved work, then use **Reopen file** to resolve the conflict without changing the active version.

## Storage and lifecycle

All production file IO uses RuneLite `Filepath`, rooted at `getPluginDirectory()/scripts`.
The exact path is displayed above the editor. Script filenames contain letters, digits, hyphens,
or underscores and end in `.mrc`; names are normalized to lowercase. Absolute paths and traversal
are not accepted by commands. Use **Add file** for external files.

`scripts.index` remembers registration, enabled status, and the two permissions, using strict `0`/`1`
flags. Older two-column indexes restore with both permissions off; malformed indexes stop loading
and are not overwritten by subsequent operations. Each script's `.state` file stores
its own persistent variables as text, and `.draft` holds shutdown recovery text. These are not
Java object serialization. File replacement uses a temporary file and an atomic move where supported.

Docking, undocking, and closing the scripts window do not stop scripts. Disabling/removing a script
or successfully replacing its active version cancels its timers. Plugin shutdown stops new effects,
cancels timers, and closes its worker without blocking RuneLite. Enabled scripts and saved variables
load again at startup; timers and local call variables do not resume. Runtime errors roll back that
invocation's variable changes and cancel that script's timers; already emitted messages cannot be undone.
Three runtime errors or limit violations within a minute automatically disable that script.
Disabling and revoking permissions take effect in memory even when saving fails; new permission
grants require a successful write. If a storage failure is reported, the previous settings on disk
can still return after restarting. Resolve the storage error and save the disabled/revoked settings
before relying on them across restarts.

## Commands

Use these in the IRC input (or the existing doubled in-game prefix):

```text
/scripts
/script list
/script load hello.mrc
/script enable hello
/script disable hello
/script reload hello
/script remove hello
/script run greet Alice
```

`load` registers an existing file in managed storage, initially disabled. It does not read arbitrary
paths. `remove` retains the file. `run` calls an alias in an enabled script using the selected buffer's
network and channel; built-in plugin commands are not overridden. Enabled scripts must have unique
public alias names. `alias -l name` is internal to its script. Aliases and variables are isolated
between scripts, including ordinary aliases and persistent variables. A returned value is shown
in the IRC System buffer.

## Supported language

Declarations are `alias [-l] name { ... }`, single-command `alias name command`, and
`on *:TEXT:pattern:target:{ ... }` (`on 1:TEXT` is also accepted without a user-level system).
Commands are separated by newlines or `|`. Braces delimit blocks outside identifier/condition
parentheses. A semicolon at the start of a statement
begins a comment to the end of the line. Literal structural braces/pipes and multiline expressions
are not supported. Identifier/condition parentheses must balance on each line; ordinary text may
contain parentheses. There is no Java evaluation or code generation.

```mirc
alias greet {
  var %name = $1
  echo -a Hello %name
}

alias add {
  return $calc($1 + $2)
}

on *:TEXT:!ping:#:{
  msg $chan pong
}

alias count {
  if (%count == $null) {
    set %count 0
  }
  inc %count
  echo -a Count: %count
}

alias remind {
  timerreminder 1 10 reminder_done $1-
}

alias reminder_done {
  echo -a Reminder: $1-
}
```

| Construct | Behavior |
| --- | --- |
| `var %name = value` | Local to the current alias/event call |
| `set %name value`, `%name = value` | Update an existing local, otherwise a persistent variable within this script |
| `set/var -n`, `-g`, `-l` | Preserve numeric text literally, force persistent scope, or force local scope; only these switches are supported |
| `inc/dec %name [amount]` | Numeric increment/decrement, default amount 1 |
| `unset %name [more names]` | Remove matching local and persistent names; `*` and `?` wildcards supported |
| `echo [-a/-s] text` | Display in the IRC System buffer only |
| `msg target text`, `notice target text` | Permission-gated reply to the originating conversation on the existing network |
| `return value` | End this alias/event call and optionally return text |
| `halt` | Unwind the current invocation, including its calling aliases; does not cancel other scripts' events |
| `aliasName arguments` | Call an alias defined in the same script |
| `$aliasName(arguments)` | Call an alias as an identifier; comma arguments become space-separated parameters |
| `if (condition) command`, `elseif`, `else` | One command or a braced body; comparisons listed below |
| `timer[name] repetitions seconds command` | Finite timer running echo, msg, notice, or an alias in this script; replaces the same name |
| `timername off`, `timers off` | Cancel one timer or all timers in this script |

Timers follow mIRC's basic named/unnamed syntax, with `/` or `.` command prefixes accepted.
Infinite repeats, zero delay, timer switches, and delayed identifier evaluation are deliberately
unavailable. Timers capture network/channel/nick context and evaluate arguments once when scheduled.
They never reinterpret expanded text as code. No operation changes
the selected game or IRC window, and no script is run automatically just by importing a file.

Expressions interpolate `%variables`, `$nick`, `$chan`, `$me`, `$network` (the plugin's network ID),
`$null` (empty), `$true`/`$false` (literal boolean tokens), `$0` (argument count), `$1`, `$1-`, and `$N-M`.
`$chan` is empty in private conversations. `$result` contains the previous command alias's return
value; `$v1`/`$v2` contain the latest evaluated comparison's operands in the current call.
`$+` joins adjacent text without surrounding spaces. Values supplied by IRC users or variables are
expanded once; they are never reparsed as commands or identifiers.

Built-in identifiers:

| Identifier | Behavior |
| --- | --- |
| `$upper(text)`, `$lower(text)` | Locale-independent case conversion |
| `$len(text)` | Java UTF-16 string length |
| `$left(text,n)`, `$right(text,n)` | Up to n UTF-16 characters; negative n selects all but abs(n) characters from the opposite end |
| `$replace(text,from,to,...)`, `$replacecs(...)` | Literal replacement pairs, case-insensitive or case-sensitive respectively |
| `$chr(n)` | UTF-16 code unit 1–65535; message control-character checks still apply |
| `$gettok(text,n,delimiter)` | Token by character code delimiter; n=0 counts, negative n counts from the end |
| `$calc(expression)` | Finite numeric arithmetic with `+ - * / % ^`, unary signs, and parentheses |

Arithmetic uses Java double precision and rounds to five decimal places; it is not an exact clone
of mIRC numeric semantics. Assignments evaluate a single spaced numeric binary operation unless
`-n` is used. Supported comparisons are `==`, `!=` (numeric when both operands are numeric,
otherwise case-insensitive text), `===` (exact text), `<`, `>`, `<=`, `>=`, `isin`/`isincs`, and
`iswm`/`iswmcs`, including negated substring/wildcard forms. Operators require surrounding spaces.
Conditions support `!`, `&&`, `||`, and parentheses; ungrouped logical operators combine left to
right. Empty, zero, and `$false` values are false. Loops, alias recursion, dynamic evaluation,
property suffixes, and general mIRC command switches are unavailable.

TEXT patterns use case-insensitive `*` and `?` wildcards, not regular expressions. Targets are `#`
for channels, `?` for private messages, `*` for either, or a channel/nick wildcard. Events use the
original sender and target before UI PM filtering. History playback, actions, notices, and the
client's own message echoes do not trigger TEXT. Only the first matching handler in each script
runs. Private events additionally require the explicit permission above. Each script processes
at most one incoming TEXT event every three seconds; intervening messages are dropped, not queued
for later execution (even a nonmatching message consumes this cooldown).

## Boundaries and limits

Scripts receive only text context and a fixed set of operations. They cannot access Java objects,
reflection, class loaders, Java compilers, native bridges, processes, arbitrary files, raw IRC lines,
new sockets, HTTP, game state, input injection, RuneScape actions, or other plugins' settings.
Only the manager can read/write managed script files and state. IRC messages reject newline/NUL/CTCP
injection and multiple-target destinations. Script output is not placed into the game's outgoing chat.
Replies to common IRC service nicknames (`*serv`, `ns`, `cs`, `bs`, `hs`, `ms`, `q`, `x`) are blocked;
this is not a complete service-account registry. Scripts cannot change IRC connection/TLS settings
or increase any runtime limit, including through their persisted variables.

- 64 registered scripts, 65,536 source characters each; imports/storage reads additionally capped at 1 MiB.
- 128 declarations per script; event patterns up to 256 characters and targets up to 100.
- 128 local and 128 persistent variables per script/call; expanded values up to 2,048 characters.
- 16 nested blocks/identifier expressions and bounded alias depth; 4,096 evaluation steps per invocation.
- A 100 ms execution deadline checked between evaluation steps, excluding any guarantee about already
  in-flight network or file IO completing within that time.
- At most 16 outputs per invocation and 4 outputs/second, 30/minute across all scripts (including echo).
- Network replies additionally require a **3-second shared gap**, at most **6 per script/minute**
  and **10 total/minute**, shared across all networks, aliases, events, and timers. The same text
  to the same network/target is suppressed for 60 seconds, including across scripts and msg/notice.
  Limits count attempted permitted sends conservatively; disabling/reloading a script does not
  reset its quota. These windows last for the current plugin lifecycle.
- Reply bodies are capped at 300 UTF-8 bytes and targets at 100 bytes. They are never split into
  multiple outbound messages to bypass limits.
- 4 active timers per script, 1–20 repetitions and intervals of 1–3,600 seconds. Timer aliases can
  schedule further finite timers, still subject to all execution/output limits. No execution occurs after the plugin lifecycle ends except already
  in-flight IO and bounded storage cleanup.
- At most 128 queued/submitted operations; overload discards incoming script events rather than
  blocking the IRC reader. IRC display continues independently.

Limit violations fail immediately; there is no deferred reply backlog. Three runtime failures
within a minute disable the offending script and cancel its timers.
These limits reduce the impact of faulty scripts; they do not make an unwanted IRC reply desirable.
Review scripts before enabling them. This subset intentionally cannot run arbitrary downloaded `.mrc`
packages unchanged, and unsupported commands or identifiers produce errors.

## Plugin Hub review

The implementation follows the linked guidance on Java 11, no extra dependencies, `Filepath` and
`Filepath.Chooser`, no reflection/native bridges/subprocesses/dynamic Java loading, and keeping IO off
the UI/client thread. It does not change existing unrelated plugin features.

RuneLite's reviewability and runtime-code restrictions may still affect acceptance of an interpreted
user-authored DSL. A bounded interpreter is not proof of Plugin Hub approval; maintainers must assess
this feature. Existing code elsewhere in the plugin is also outside this feature's compliance claim.

References: [agent guidelines](https://github.com/runelite/plugin-hub-tooling/blob/v5/templateplugin/AGENTS.md),
[rejected features and restricted APIs](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features).
Compatibility references: mIRC's [aliases](https://www.mirc.com/help/html/aliases.html),
[variables](https://www.mirc.com/help/html/variables.html),
[conditions](https://www.mirc.com/help/html/if_then_else.html),
[commands](https://www.mirc.com/help/html/mirc_commands.html), and
[text/number identifiers](https://www.mirc.com/help/html/text_number_identifiers.html).
Compatibility is based on the documented subset and regression tests, not comparison against a live mIRC runtime.

## Manual verification

Automated tests cover the interpreter, persistence/reload transactions, timers/lifecycle, event filtering,
and editor operations in isolation. They do not confirm behavior in the running game client.

1. Open Scripts from the docked toolbar. Create a script, save it, tick Enabled, and run
   `/script run greet Alice`. Confirm output in the IRC System buffer.
2. Undock and redock IRC; reopen Scripts and confirm the same list and enabled state.
3. Confirm replies are blocked by default. Grant reply permission, then on an IRC test channel,
   send `!ping` from another client and confirm one `pong` on that network. Repeat rapidly: no flood
   or delayed burst should occur. Own echoes and bouncer history must not trigger it. Private
   events require their separate permission; replies to other conversations remain blocked.
4. Introduce an unsupported command and press Save & Reload. Confirm the error line and that the
   old active script still runs. Test Save-only, then Reload disk.
5. Test find/replace, indentation, undo, unsaved switching/closing, and an external file conflict.
6. Schedule a reminder, then disable/reload the script before it fires. Confirm no reminder.
7. Disable and re-enable the plugin. Confirm enabled files and persistent variables return, timers
   do not resume, and an unsaved draft is offered as editor text without being executed.
8. Test Remove (file retained), re-add with `/script load`, and Delete file on a disposable managed copy.

For a development-client login, follow [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts).
