package com.irc.scripting;

import org.junit.Test;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class ScriptReplyLimiterTest {
    private final AtomicLong time = new AtomicLong();
    private final ScriptReplyLimiter limiter = new ScriptReplyLimiter(time::get);
    private final ScriptContext context = new ScriptContext("a", "#test", "Alice", "me", "");
    private void seconds(long value) { time.set(TimeUnit.SECONDS.toNanos(value)); }
    private void send(String script, String text) { limiter.acquire(script, context, "#test", text); }
    private void denied(Runnable action, String reason) {
        try { action.run(); fail("Expected " + reason); }
        catch (IllegalArgumentException ex) { assertTrue(ex.getMessage(), ex.getMessage().contains(reason)); }
    }

    @Test public void cooldownAndDuplicateSuppressionAreSharedAcrossScriptsAndTargets() {
        send("one", "hello"); seconds(2);
        denied(() -> limiter.acquire("two", context, "#other", "new"), "reply limit");
        seconds(3);
        denied(() -> limiter.acquire("two", context, "#TEST", "hello"), "Duplicate");
        send("two", "new"); seconds(59);
        denied(() -> send("three", "hello"), "Duplicate");
        seconds(60); send("three", "hello");
    }

    @Test public void oneScriptCannotExhaustTheSharedMinuteAllowance() {
        for (int i = 0; i < 6; i++) { seconds(i * 3); send("one", "reply " + i); }
        seconds(18); denied(() -> send("one", "seventh"), "reply limit");
        send("two", "seventh");
        seconds(60); send("one", "expired");
    }

    @Test public void globalMinuteAllowanceCoversDifferentNetworksAndScripts() {
        for (int i = 0; i < 10; i++) { seconds(i * 3); send("script" + i, "reply " + i); }
        seconds(30);
        denied(() -> limiter.acquire("fresh", new ScriptContext("b", "#other", "B", "me", ""), "#other", "new"), "reply limit");
        seconds(60); send("fresh", "new");
    }
}
