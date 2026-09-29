package com.irc.protocol;

import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class IrcOutputTest {
    @Test public void outputIsBoundedAndClosedQueuesRejectWork() {
        IrcOutput output = new IrcOutput();
        for (int i = 0; i < 128; i++) assertTrue(output.offer("PRIVMSG #a :test", false, null));
        assertFalse(output.offer("PRIVMSG #a :overflow", false, null));
        assertTrue(output.offer("PONG :ping", true, null));
        output.close();
        assertFalse(output.offer("PONG :ping", true, null));
    }

    @Test public void protocolBypassesFloodPacingAndEchoFollowsFlush() throws Exception {
        IrcOutput output = new IrcOutput();
        StringWriter wire = new StringWriter();
        List<Long> times = new CopyOnWriteArrayList<>();
        CountDownLatch sent = new CountDownLatch(3);
        Runnable commandSent = () -> { times.add(System.nanoTime()); sent.countDown(); };
        output.offer("PRIVMSG #a :first", false, commandSent);
        output.offer("PRIVMSG #a :second", false, commandSent);
        output.offer("PONG :token", true, () -> {
            assertTrue(wire.toString().startsWith("PONG :token\r\n"));
            sent.countDown();
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> job = worker.submit(() -> output.run(new BufferedWriter(wire), e -> { throw new AssertionError(e); }, line -> {}));
            assertTrue(sent.await(3, TimeUnit.SECONDS));
            assertTrue(times.get(1) - times.get(0) >= TimeUnit.MILLISECONDS.toNanos(500));
            output.close();
            job.get(2, TimeUnit.SECONDS);
        } finally { output.close(); worker.shutdownNow(); }
    }

    @Test public void failedFlushReportsFailureWithoutEcho() {
        IrcOutput output = new IrcOutput();
        AtomicBoolean echoed = new AtomicBoolean();
        AtomicBoolean failed = new AtomicBoolean();
        output.offer("PRIVMSG #a :test", false, () -> echoed.set(true));
        output.run(new BufferedWriter(new Writer() {
            @Override public void write(char[] c, int offset, int length) { }
            @Override public void flush() throws IOException { throw new IOException("broken socket"); }
            @Override public void close() { }
        }), e -> failed.set(true), line -> {});
        assertTrue(failed.get());
        assertFalse(echoed.get());
        output.close();
    }
}
