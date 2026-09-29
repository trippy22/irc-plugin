package com.irc.protocol;

import org.junit.Test;
import java.io.*;
import java.util.Arrays;
import static org.junit.Assert.*;

public class IrcLineTest {
    @Test public void parsesTagsPrefixAndTrailingParameterWithoutState() {
        IrcLine line = IrcLine.parse("@time=2026-09-28T12:00:00Z;label=hello\\sworld\\:x :Alice!u@h privmsg #room :hello there");
        assertEquals("Alice!u@h", line.source);
        assertEquals("PRIVMSG", line.command);
        assertEquals(Arrays.asList("#room", "hello there"), line.params);
        assertEquals("hello world;x", line.tags.get("label"));
        assertEquals(Arrays.asList(""), IrcLine.parse("PING :").params);
        assertNull(IrcLine.parse("@unfinished"));
        assertNull(IrcLine.parse("PRIVMSG #a :one\nQUIT"));
    }
    @Test(expected = IOException.class) public void receiveLimitAppliesBeforeWholeLineIsAllocated() throws Exception {
        IrcLine.read(new BufferedReader(new StringReader("x".repeat(8193))));
    }
}
