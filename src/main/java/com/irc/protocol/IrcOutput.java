package com.irc.protocol;

import java.io.BufferedWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/** One bounded, paced writer per session. Socket writes never run on a caller's thread. */
final class IrcOutput implements AutoCloseable {
    private static final int LIMIT = 128;
    private static final long INTERVAL_NS = 600_000_000L;
    private final Deque<Line> commands = new ArrayDeque<>();
    private final Deque<Line> protocol = new ArrayDeque<>();
    private boolean closed;

    synchronized boolean offer(String text, boolean urgent, Runnable sent) {
        return offer(text, urgent, sent, null);
    }

    synchronized boolean offer(String text, boolean urgent, Runnable sent, String key) {
        Deque<Line> queue = urgent ? protocol : commands;
        if (closed || queue.size() >= LIMIT) return false;
        queue.addLast(new Line(text, sent, key));
        notifyAll();
        return true;
    }

    synchronized boolean cancelMatching(java.util.function.Predicate<String> keyMatches) {
        return commands.removeIf(line -> line.key != null && keyMatches.test(line.key));
    }

    void run(BufferedWriter writer, Consumer<IOException> failure, Consumer<String> log) {
        long nextCommand = 0;
        try {
            while (true) {
                Line line;
                synchronized (this) {
                    while (true) {
                        if (closed) return;
                        if (!protocol.isEmpty()) {
                            line = protocol.removeFirst();
                            break;
                        }
                        long remaining = nextCommand - System.nanoTime();
                        if (!commands.isEmpty() && remaining <= 0) {
                            line = commands.removeFirst();
                            nextCommand = System.nanoTime() + INTERVAL_NS;
                            break;
                        }
                        wait(commands.isEmpty() ? 0 : Math.max(1, remaining / 1_000_000));
                    }
                }
                log.accept(line.text);
                writer.write(line.text + "\r\n");
                writer.flush();
                if (line.sent != null) line.sent.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            failure.accept(e);
        }
    }

    synchronized void discardCommands() {
        commands.clear();
    }

    @Override
    public synchronized void close() {
        closed = true;
        commands.clear();
        protocol.clear();
        notifyAll();
    }

    private static final class Line {
        final String text;
        final Runnable sent;
        final String key;
        Line(String text, Runnable sent, String key) { this.text = text; this.sent = sent; this.key = key; }
    }
}
