package com.irc.scripting;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class IrcScriptTest {
    private final ScriptContext context = new ScriptContext("swift", "#test", "Alice", "me", "!ping");
    private final Map<String, String> globals = new LinkedHashMap<>();
    private final List<String> output = new ArrayList<>();
    private final IrcScript.Host host = new IrcScript.Host() {
        public void echo(String text) { output.add("echo " + text); }
        public void send(String target, String text, boolean notice) { output.add((notice ? "notice " : "msg ") + target + " " + text); }
        public void timer(String name, int repetitions, int seconds, Runnable action, ScriptContext c) { output.add("timer " + name + " " + repetitions + " " + seconds + " " + c.network); action.run(); }
        public void cancelTimer(String name) { output.add("cancel " + name); }
    };

    @Test public void executesSamplesAndReturnsAliasValues() {
        IrcScript script = IrcScript.compile("alias greet {\n var %name = $1\n echo -a Hello %name\n}\n"
                + "alias add { return $calc($1 + $2) }\non *:TEXT:!ping:#:{ msg $chan pong }");
        script.run("greet", "Alice", context, globals, host);
        assertEquals("30", script.run("add", "10 20", context, globals, host));
        script.text(context, globals, host);
        assertEquals(Arrays.asList("echo Hello Alice", "msg #test pong"), output);
    }

    @Test public void scopesVariablesAndSupportsNestedFunctionsAndConditionals() {
        IrcScript script = IrcScript.compile("alias inner { var %x = local | return $upper($gettok(a b c,-1,32)) }\n"
                + "alias outer { set %x = saved | var %r = $inner() | if (%r == C) { return %x $calc(2 + 3 * 4) } else { return bad } }");
        assertEquals("saved 14", script.run("outer", "", context, globals, host));
        assertEquals("saved", globals.get("x"));
    }

    @Test public void supportsElseAndParametersAndConcatenation() {
        IrcScript script = IrcScript.compile("alias test { if ($1 > 3) { return yes } else { return $0 $2- $+ ! } }");
        assertEquals("3 hello world!", script.run("test", "2 hello world", context, globals, host));
        assertEquals("yes", script.run("test", "4", context, globals, host));
    }

    @Test public void incomingTextAndVariablesAreNeverReparsedAsCode() {
        IrcScript script = IrcScript.compile("alias test { set %input = $1- | echo %input }\non *:TEXT:*:?:{ echo $nick $1- }");
        String hostile = "$upper(secret) | msg #other injected";
        script.run("test", hostile, context, globals, host);
        script.text(new ScriptContext("other", "Bob", "Bob", "me", hostile), globals, host);
        assertEquals(Arrays.asList("echo " + hostile, "echo Bob " + hostile), output);
    }

    @Test public void rejectsUnsupportedCommandsIdentifiersAndSyntaxAtLoad() {
        for (String command : Arrays.asList("run cmd.exe", "dll thing", "raw PRIVMSG #x :bad", "sockopen a b c", "write secret text",
                "load file", "java System.exit(0)", "join #chan", "while (1 == 1) { echo x }")) {
            fails(() -> IrcScript.compile("alias test {\n " + command + "\n}"), "Line 2");
        }
        fails(() -> IrcScript.compile("alias test { echo $read(secret) }"), "Unsupported identifier");
        fails(() -> IrcScript.compile("alias test { echo x"), "Missing }");
        fails(() -> IrcScript.compile("alias test { else { echo x } }"), "else must follow");
    }

    @Test public void boundedRecursionArithmeticAndOutput() {
        IrcScript script = IrcScript.compile("alias recurse { recurse }\nalias math { return $calc($1-) }");
        fails(() -> script.run("recurse", "", context, globals, host), "Recursive alias");
        fails(() -> script.run("math", "1 / 0", context, globals, host), "Invalid arithmetic");
        fails(() -> script.run("math", "-".repeat(40) + "1", context, globals, host), "nesting limit");
        IrcScript flood = IrcScript.compile("alias flood {\n" + "echo x\n".repeat(17) + "}");
        fails(() -> flood.run("flood", "", context, globals, host), "Output limit");
        assertEquals(16, output.size());
        fails(() -> IrcScript.compile(" ".repeat(IrcScript.MAX_SOURCE + 1)), "too large");
    }

    @Test public void rejectsMessageInjectionAndMultiTargetSends() {
        IrcScript script = IrcScript.compile("alias send { msg $1 $2- }");
        fails(() -> script.run("send", "#one,#two hello", context, globals, host), "single IRC target");
        IrcScript echo = IrcScript.compile("alias send { msg #test %text }");
        globals.put("text", "hello\r\nPRIVMSG #other :bad");
        fails(() -> echo.run("send", "", context, globals, host), "Control characters");
        globals.put("text", "\1DCC SEND thing\1");
        fails(() -> echo.run("send", "", context, globals, host), "Control characters");
        assertTrue(output.isEmpty());
    }

    @Test public void timersOnlyReferenceDefinedAliasesAndCaptureIrcContext() {
        IrcScript script = IrcScript.compile("alias later { echo $1 }\nalias start { timerreminder 1 2 later hello | timerreminder off }");
        script.run("start", "", context, globals, host);
        assertEquals(Arrays.asList("timer reminder 1 2 swift", "echo hello", "cancel reminder"), output);
        fails(() -> IrcScript.compile("alias start { timerx 1 1 missing }").run("start", "", context, globals, host), "timer command");
    }

    @Test public void wildcardMatchingIsCaseInsensitiveAndLiteralOtherwise() {
        assertTrue(IrcScript.glob("!PING *?", "!ping Hello"));
        assertFalse(IrcScript.glob("(a+)+$", "aaaaaaaa"));
        assertTrue(IrcScript.glob("[literal]", "[literal]"));
    }

    @Test public void supportsMircAssignmentsLocalsAndLiteralNumericValues() {
        IrcScript script = IrcScript.compile("alias test { set %saved.value 2 + 3 | var %local = 1 | set %local 2 | inc %local 3 | dec %local | set -n %literal 2 + 3 | %assigned = 9 | return %saved.value %local %literal %assigned }\n"
                + "alias clear { unset %saved.* %assigned }");
        assertEquals("5 4 2 + 3 9", script.run("test", "", context, globals, host));
        assertFalse(globals.containsKey("local"));
        script.run("clear", "", context, globals, host);
        assertEquals(Collections.singletonMap("literal", "2 + 3"), globals);
    }

    @Test public void haltUnwindsCallersButReturnProvidesResult() {
        IrcScript script = IrcScript.compile("alias -l stop halt\nalias value return success\n"
                + "alias outer { value | echo $result | stop | echo forbidden }");
        assertFalse(script.aliasNames().contains("stop"));
        assertEquals("", script.run("outer", "", context, globals, host));
        assertEquals(Arrays.asList("echo success"), output);
    }

    @Test public void firstTextMatchAndPrivateChannelSemantics() {
        IrcScript script = IrcScript.compile("on 1:TEXT:*:?:{ echo first $chan $nick }\non *:TEXT:*:*:{ echo second }");
        script.text(new ScriptContext("a", "Bob", "Bob", "me", "hi"), globals, host);
        assertEquals(Arrays.asList("echo first  Bob"), output);
    }

    @Test public void supportsInlineBranchesAndBoundedLogicalConditions() {
        IrcScript script = IrcScript.compile("alias test { if (($1 == 01) && (!$false)) return numeric\n"
                + "elseif ($1 === EXACT) return case\nelse return no\n}\n"
                + "alias wild { if (a* iswm $1) return $v1 $v2 | return no }\n"
                + "alias negative { if (!(($1 == 1) || ($1 == 2))) return other | return small }");
        assertEquals("numeric", script.run("test", "1", context, globals, host));
        assertEquals("case", script.run("test", "EXACT", context, globals, host));
        assertEquals("no", script.run("test", "exact", context, globals, host));
        assertEquals("a* Alice", script.run("wild", "Alice", context, globals, host));
        assertEquals("small", script.run("negative", "2", context, globals, host));
        assertEquals("other", script.run("negative", "3", context, globals, host));
        fails(() -> IrcScript.compile("alias bad { if (" + "!(".repeat(20) + "1" + ")".repeat(20) + ") echo bad }"), "nesting limit");
    }

    @Test public void stringFunctionsAndLiteralParentheses() {
        IrcScript script = IrcScript.compile("alias test { echo :-) | return $left(abcdef,-2) $right(abcdef,-2) $replace(AbA,a,x,b,y) $replacecs(AbA,a,x) $chr(65) $calc(2 ^ 3) }");
        assertEquals("abcd cdef xyx AbA A 8", script.run("test", "", context, globals, host));
        assertEquals(Arrays.asList("echo :-)"), output);
        fails(() -> IrcScript.compile("alias bad { msg #test $chr(10) }").run("bad", "", context, globals, host), "Control characters");
    }

    @Test public void timersRejectInfiniteAndImmediateRunsAndNeverReevaluateArguments() {
        for (String timing : Arrays.asList("0 1", "1 0", "21 1", "1 3601")) {
            fails(() -> IrcScript.compile("alias start { timerx " + timing + " echo hi }").run("start", "", context, globals, host), "Timers require");
        }
        IrcScript script = IrcScript.compile("alias start { .timerx 1 1 echo $1- | timers off }");
        String hostile = "$upper(secret) | msg #other injected";
        script.run("start", hostile, context, globals, host);
        assertEquals(Arrays.asList("timer x 1 1 swift", "echo " + hostile, "cancel *"), output);
    }

    private static void fails(Runnable action, String expected) {
        try { action.run(); fail("Expected " + expected); }
        catch (IllegalArgumentException ex) { assertTrue(ex.getMessage(), ex.getMessage().contains(expected)); }
    }
}
