package com.irc.scripting;

import net.runelite.client.util.Filepath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** One runtime per plugin lifecycle, shared by all IRC views. State and IO are worker-confined. */
final class ScriptManager {
    interface Store {
        String read(String name) throws IOException;
        void write(String name, String value) throws IOException;
        void delete(String name) throws IOException;
        String path(String name) throws IOException;
    }
    interface Output {
        void echo(ScriptContext context, String text);
        void send(ScriptContext context, String target, String text, boolean notice);
    }
    interface Work<T> { T run() throws Exception; }
    static final class View {
        final String name, path, status;
        final boolean enabled, running, replies, privateMessages;
        View(Entry e, String path) { name = e.name; this.path = path; enabled = e.enabled; running = e.program != null; replies = e.replies; privateMessages = e.privateMessages; status = e.error.isEmpty() ? (running ? "Running" : "Disabled") : e.error; }
    }
    static final class Document {
        final String source, disk;
        Document(String source, String disk) { this.source = source; this.disk = disk; }
    }
    private static final class Entry {
        final String name;
        boolean enabled, replies, privateMessages; String error = "";
        IrcScript program;
        long lastEvent, failureWindow; int failures;
        Map<String, String> globals = new LinkedHashMap<>();
        final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
        Entry(String name, boolean enabled) { this.name = name; this.enabled = enabled; }
    }
    private final Store store;
    private final Output output;
    private final ScheduledThreadPoolExecutor worker;
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger pending = new AtomicInteger();
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<ScheduledFuture<?>, Boolean> timers = new ConcurrentHashMap<>();
    private final List<Consumer<List<View>>> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Long> sends = new ArrayDeque<>();
    private final ScriptReplyLimiter replyLimiter = new ScriptReplyLimiter(System::nanoTime);
    private volatile List<View> snapshot = Collections.emptyList();
    private volatile boolean closed;
    private volatile String storageError = "";
    private final Object lifecycle = new Object();
    private final CompletableFuture<Void> ready;

    ScriptManager(Store store, Output output) {
        this(store, output, CompletableFuture.completedFuture(null));
    }

    ScriptManager(Store store, Output output, CompletableFuture<Void> predecessor) {
        this.store = store; this.output = output;
        worker = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "irc-scripts"); thread.setDaemon(true); return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        ready = predecessor.handle((ignored, error) -> null).thenCompose(ignored -> enqueue(() -> { restore(); return null; }));
    }

    CompletableFuture<Void> ready() { return ready; }
    List<View> snapshot() { return snapshot; }
    void listen(Consumer<List<View>> listener) { listeners.add(listener); listener.accept(snapshot); }
    void unlisten(Consumer<List<View>> listener) { listeners.remove(listener); }

    private <T> CompletableFuture<T> submit(Work<T> work) {
        if (closed) {
            CompletableFuture<T> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new IllegalStateException("Scripts are stopped"));
            return rejected;
        }
        if (pending.incrementAndGet() > 128) {
            pending.decrementAndGet();
            CompletableFuture<T> rejected = new CompletableFuture<>();
            rejected.completeExceptionally(new IllegalStateException("Script queue is full"));
            return rejected;
        }
        return ready.thenCompose(ignored -> enqueue(work)).whenComplete((ignored, error) -> pending.decrementAndGet());
    }

    private <T> CompletableFuture<T> enqueue(Work<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        synchronized (lifecycle) {
            if (closed || queued.get() >= 128) {
                future.completeExceptionally(new IllegalStateException(closed ? "Scripts are stopped" : "Script queue is full"));
                return future;
            }
            queued.incrementAndGet();
            try {
                worker.execute(() -> {
                    try {
                        if (closed) throw new IllegalStateException("Scripts are stopped");
                        future.complete(work.run());
                    } catch (Exception ex) { future.completeExceptionally(ex); }
                    finally { queued.decrementAndGet(); }
                });
            } catch (RejectedExecutionException ex) { queued.decrementAndGet(); future.completeExceptionally(ex); }
        }
        return future;
    }

    private void restore() throws IOException {
        try {
            String manifest = store.read("scripts.index");
            if (manifest != null) {
                for (String line : manifest.split("\\R")) {
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\t", -1);
                    if ((parts.length != 2 && parts.length != 4) || !flag(parts[1])
                            || (parts.length == 4 && (!flag(parts[2]) || !flag(parts[3]))))
                        throw new IOException("Invalid scripts.index; restore it before changing scripts");
                    String name = filename(parts[0]);
                    if (entries.size() >= 64 || entries.containsKey(name)) throw new IOException("Invalid script list");
                    Entry e = new Entry(name, parts[1].equals("1"));
                    if (parts.length == 4) { e.replies = parts[2].equals("1"); e.privateMessages = parts[3].equals("1"); }
                    entries.put(name, e);
                }
            }
            for (Entry e : entries.values()) {
                try {
                    e.globals = readGlobals(store.read(e.name + ".state"));
                    if (e.enabled) e.program = compile(e, requireSource(e.name));
                } catch (Exception ex) { e.error = message(ex); }
            }
        } catch (Exception ex) {
            storageError = message(ex);
            entries.clear();
            throw new IOException(storageError, ex);
        } finally { publish(); }
    }

    static String filename(String value) {
        String name = value.trim().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".mrc")) name += ".mrc";
        if (!name.matches("[a-z0-9][a-z0-9_-]{0,59}\\.mrc")
                || name.matches("(?:con|prn|aux|nul|com[0-9]|lpt[0-9])\\.mrc"))
            throw new IllegalArgumentException("Use a script name with letters, digits, - or _ (not an absolute path)");
        return name;
    }

    private static boolean flag(String value) { return value.equals("0") || value.equals("1"); }

    /** Called only by the manager UI. No language command can grant capabilities. */
    CompletableFuture<Void> permissions(String name, boolean replies, boolean privateMessages) {
        return submit(() -> {
            Entry e = entry(name);
            boolean oldReplies = e.replies, oldPrivate = e.privateMessages;
            e.replies = replies; e.privateMessages = privateMessages;
            try { saveIndex(); } catch (IOException ex) {
                // A failed write may not grant a new capability or undo a revocation in memory.
                e.replies = oldReplies && replies; e.privateMessages = oldPrivate && privateMessages;
                cancelTimers(e); publish(); throw ex;
            }
            cancelTimers(e); publish(); return null;
        });
    }

    private Entry entry(String name) {
        if (!storageError.isEmpty()) throw new IllegalStateException(storageError);
        Entry e = entries.get(filename(name));
        if (e == null) throw new IllegalArgumentException("Script is not in the list: " + name);
        return e;
    }

    CompletableFuture<Document> document(String name) {
        return submit(() -> {
            Entry e = entry(name); String disk = requireSource(e.name), draft = store.read(e.name + ".draft");
            if (draft != null) checkSource(draft);
            return new Document(draft == null ? disk : draft, disk);
        });
    }

    CompletableFuture<Void> add(String name, String source) {
        return submit(() -> { addNow(name, source); return null; });
    }

    CompletableFuture<Void> importFile(Filepath file, String name) {
        return submit(() -> { addNow(name, ScriptFiles.readFile(file)); return null; });
    }

    private void addNow(String name, String source) throws IOException {
        if (!storageError.isEmpty()) throw new IOException(storageError);
        name = filename(name);
        if (entries.size() >= 64) throw new IllegalArgumentException("At most 64 scripts can be registered");
        if (entries.containsKey(name)) throw new IllegalArgumentException("Script already exists");
        if (source != null) {
            checkSource(source);
            if (store.read(name) != null) throw new IllegalArgumentException("File already exists; use /script load " + name);
            store.write(name, source);
        } else requireSource(name);
        Entry e = new Entry(name, false);
        e.globals = readGlobals(store.read(name + ".state"));
        entries.put(name, e);
        try { saveIndex(); } catch (IOException ex) { entries.remove(name); throw ex; }
        publish();
    }

    CompletableFuture<Void> enable(String name, boolean enabled) {
        return submit(() -> {
            Entry e = entry(name);
            // Stopping execution must work even when storage is unavailable.
            if (!enabled) { e.enabled = false; e.program = null; cancelTimers(e); publish(); }
            IrcScript replacement;
            try { replacement = enabled ? compile(e, requireSource(e.name), true) : null; }
            catch (Exception ex) { e.error = message(ex); publish(); throw ex; }
            boolean previous = e.enabled;
            e.enabled = enabled;
            try { saveIndex(); } catch (IOException ex) { e.enabled = previous; throw ex; }
            cancelTimers(e); e.program = replacement; e.error = ""; e.failures = 0; publish(); return null;
        });
    }

    CompletableFuture<Void> save(String name, String source, String expectedDisk, boolean reload) {
        return submit(() -> {
            Entry e = entry(name); checkSource(source);
            if (expectedDisk != null && !requireSource(e.name).equals(expectedDisk))
                throw new IOException("The file changed on disk. Reopen it before saving to avoid overwriting those changes.");
            IrcScript replacement;
            try { replacement = reload ? compile(e, source) : null; }
            catch (Exception ex) { e.error = message(ex); publish(); throw ex; }
            store.write(e.name, source);
            store.delete(e.name + ".draft");
            if (reload && e.enabled) { cancelTimers(e); e.program = replacement; }
            e.error = reload ? "" : (e.program == null ? "Saved; disabled" : "Saved; reload to apply");
            publish(); return null;
        });
    }

    CompletableFuture<Void> reload(String name) {
        return submit(() -> {
            Entry e = entry(name);
            try {
                IrcScript replacement = compile(e, requireSource(e.name));
                if (e.enabled) { cancelTimers(e); e.program = replacement; }
                e.error = "";
            } catch (Exception ex) { e.error = message(ex); throw ex; }
            finally { publish(); }
            return null;
        });
    }

    CompletableFuture<Void> remove(String name, boolean deleteFile) {
        return submit(() -> {
            Entry e = entry(name);
            // Persist removal before unloading; failed writes leave the running definition intact.
            Map<String, Entry> previous = new LinkedHashMap<>(entries);
            entries.remove(e.name);
            try { saveIndex(); } catch (IOException ex) { entries.clear(); entries.putAll(previous); throw ex; }
            cancelTimers(e); e.program = null;
            try {
                if (deleteFile) { store.delete(e.name); store.delete(e.name + ".state"); store.delete(e.name + ".draft"); }
            } finally { publish(); }
            return null;
        });
    }

    CompletableFuture<Void> discardDraft(String name) { return submit(() -> { store.delete(filename(name) + ".draft"); return null; }); }

    private IrcScript compile(Entry e, String source) {
        return compile(e, source, e.enabled);
    }

    private IrcScript compile(Entry e, String source, boolean active) {
        IrcScript program = IrcScript.compile(source);
        if (active) {
            for (Entry other : entries.values()) {
                if (other == e || other.program == null) continue;
                for (String name : program.aliasNames()) if (other.program.aliasNames().contains(name))
                    throw new IllegalArgumentException("Alias " + name + " is already provided by " + other.name);
            }
        }
        return program;
    }

    private String requireSource(String name) throws IOException {
        String source = store.read(name);
        if (source == null) throw new IOException("Missing script: " + name);
        checkSource(source); return source;
    }
    private static void checkSource(String source) {
        if (source.length() > IrcScript.MAX_SOURCE) throw new IllegalArgumentException("Script exceeds 65536 characters");
        if (source.indexOf('\0') >= 0) throw new IllegalArgumentException("Script contains a NUL character");
    }

    CompletableFuture<String> run(String alias, String args, ScriptContext context) {
        return submit(() -> {
            String name = alias.toLowerCase(Locale.ROOT);
            for (Entry e : entries.values()) if (e.program != null && e.program.aliasNames().contains(name))
                return execute(e, () -> e.program.run(name, args, context, e.globals, host(e, context)));
            throw new IllegalArgumentException("No enabled script defines alias " + alias);
        });
    }

    void text(ScriptContext context) {
        if (context.text.length() > IrcScript.MAX_VALUE || closed) return;
        submit(() -> {
            for (Entry e : entries.values()) if (e.program != null && !closed) {
                if (!context.isChannel() && !e.privateMessages) continue;
                long now = System.nanoTime();
                if (e.lastEvent != 0 && now - e.lastEvent < TimeUnit.SECONDS.toNanos(3)) continue;
                e.lastEvent = now;
                try { execute(e, () -> { e.program.text(context, e.globals, host(e, context)); return ""; }); }
                catch (Exception ex) { /* execute reports once in the manager; incoming IRC must keep flowing. */ }
            }
            return null;
        });
    }

    private String execute(Entry e, Work<String> action) throws Exception {
        Map<String, String> before = new LinkedHashMap<>(e.globals);
        try {
            String result = action.run();
            if (!before.equals(e.globals)) store.write(e.name + ".state", writeGlobals(e.globals));
            return result;
        } catch (Exception ex) {
            e.globals.clear(); e.globals.putAll(before);
            cancelTimers(e);
            String error = message(ex);
            long now = System.nanoTime();
            if (e.failureWindow == 0 || now - e.failureWindow >= TimeUnit.MINUTES.toNanos(1)) { e.failureWindow = now; e.failures = 0; }
            if (++e.failures >= 3) {
                e.enabled = false; e.program = null;
                error = "Automatically disabled after repeated errors: " + error;
                try { saveIndex(); } catch (IOException storage) { error += " (unable to persist disabled state)"; }
            }
            if (!error.equals(e.error)) { e.error = error; publish(); }
            throw ex;
        }
    }

    private IrcScript.Host host(Entry e, ScriptContext context) {
        return new IrcScript.Host() {
            public void echo(String text) {
                if (!closed) { rateLimit(); output.echo(context, text); }
            }
            public void send(String target, String text, boolean notice) {
                if (!closed) {
                    if (!e.replies) throw new IllegalArgumentException("IRC replies are not permitted for this script");
                    if (!context.isChannel() && !e.privateMessages) throw new IllegalArgumentException("Private conversations are not permitted for this script");
                    if (!target.equalsIgnoreCase(context.channel) || target.equalsIgnoreCase("System"))
                        throw new IllegalArgumentException("Scripts may only reply to the originating conversation");
                    if (text.getBytes(StandardCharsets.UTF_8).length > 300 || target.getBytes(StandardCharsets.UTF_8).length > 100)
                        throw new IllegalArgumentException("Script replies are limited to 300 UTF-8 bytes and targets to 100 bytes");
                    String folded = target.toLowerCase(Locale.ROOT);
                    if (!context.isChannel() && (folded.endsWith("serv") || java.util.Arrays.asList("ns", "cs", "bs", "hs", "ms", "q", "x").contains(folded)))
                        throw new IllegalArgumentException("Script messages to IRC services are not permitted");
                    replyLimiter.acquire(e.name, context, target, text);
                    rateLimit(); output.send(context, target, text, notice);
                }
            }
            public void timer(String name, int repetitions, int seconds, Runnable action, ScriptContext captured) {
                synchronized (lifecycle) {
                    if (closed) return;
                    if (name.isEmpty()) {
                        int next = 1; while (e.timers.containsKey(Integer.toString(next))) next++;
                        name = Integer.toString(next);
                    }
                    if (!e.timers.containsKey(name) && e.timers.size() >= 4) throw new IllegalArgumentException("At most 4 timers per script");
                    cancelTimer(name);
                    scheduleTimer(e, name, repetitions, seconds, action);
                }
            }
            public void cancelTimer(String name) {
                for (String candidate : new ArrayList<>(e.timers.keySet())) if (IrcScript.glob(name, candidate)) {
                    ScheduledFuture<?> future = e.timers.remove(candidate);
                    if (future != null) { future.cancel(false); timers.remove(future); }
                }
            }
        };
    }

    private void scheduleTimer(Entry e, String name, int repetitions, int seconds, Runnable action) {
        final class Repeat implements Runnable {
            ScheduledFuture<?> future;
            public void run() {
                if (closed || e.program == null || e.timers.get(name) != future) return;
                timers.remove(future);
                try { execute(e, () -> { action.run(); return ""; }); }
                catch (Exception ex) { return; }
                synchronized (lifecycle) {
                    if (e.timers.remove(name, future) && repetitions > 1 && !closed && e.program != null)
                        scheduleTimer(e, name, repetitions - 1, seconds, action);
                }
            }
        }
        Repeat task = new Repeat();
        task.future = worker.schedule(task, seconds, TimeUnit.SECONDS);
        e.timers.put(name, task.future); timers.put(task.future, true);
    }

    private void rateLimit() {
        long now = System.nanoTime();
        while (!sends.isEmpty() && now - sends.peekFirst() >= TimeUnit.MINUTES.toNanos(1)) sends.removeFirst();
        if (sends.size() >= 30) throw new IllegalArgumentException("Script output rate limit: 30 messages/minute");
        int recent = 0;
        for (long time : sends) if (now - time < TimeUnit.SECONDS.toNanos(1)) recent++;
        if (recent >= 4) throw new IllegalArgumentException("Script output rate limit: 4 messages/second");
        sends.addLast(now);
    }

    private void cancelTimers(Entry e) {
        for (ScheduledFuture<?> future : e.timers.values()) { future.cancel(false); timers.remove(future); }
        e.timers.clear();
    }

    private void saveIndex() throws IOException {
        StringBuilder out = new StringBuilder();
        for (Entry e : entries.values()) out.append(e.name).append('\t').append(e.enabled ? '1' : '0')
                .append('\t').append(e.replies ? '1' : '0').append('\t').append(e.privateMessages ? '1' : '0').append('\n');
        store.write("scripts.index", out.toString());
    }

    private void publish() {
        List<View> views = new ArrayList<>();
        for (Entry e : entries.values()) {
            String path;
            try { path = store.path(e.name); } catch (IOException ex) { path = e.name; }
            views.add(new View(e, path));
        }
        snapshot = Collections.unmodifiableList(views);
        if (!closed) for (Consumer<List<View>> listener : listeners) listener.accept(snapshot);
    }

    static String message(Throwable ex) {
        while (ex.getCause() != null && (ex instanceof java.util.concurrent.CompletionException || ex instanceof java.util.concurrent.ExecutionException)) ex = ex.getCause();
        return ex.getMessage() == null ? "Script operation failed" : ex.getMessage();
    }

    static String writeGlobals(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((name, value) -> out.append(name).append('\t')
                .append(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))).append('\n'));
        return out.toString();
    }
    static Map<String, String> readGlobals(String source) {
        Map<String, String> result = new LinkedHashMap<>();
        if (source == null) return result;
        for (String line : source.split("\\R")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\t", -1);
            if (parts.length != 2 || !parts[0].matches("[a-z_][a-z_0-9.]*")) throw new IllegalArgumentException("Invalid saved variables");
            String value = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            if (value.length() > IrcScript.MAX_VALUE || result.size() >= IrcScript.MAX_VARIABLES) throw new IllegalArgumentException("Saved variables exceed limits");
            result.put(parts[0], value);
        }
        return result;
    }

    /** Stops effects immediately; finishes bounded storage work without waiting on RuneLite's thread. */
    CompletableFuture<Void> close(String draftName, String draftText) {
        CompletableFuture<Void> saved = new CompletableFuture<>();
        synchronized (lifecycle) {
            if (closed) { saved.complete(null); return saved; }
            closed = true;
            for (ScheduledFuture<?> timer : timers.keySet()) timer.cancel(false);
            timers.clear(); listeners.clear();
            worker.execute(() -> {
                try {
                    if (draftName != null && draftText != null) {
                        checkSource(draftText); store.write(filename(draftName) + ".draft", draftText);
                    }
                    saved.complete(null);
                } catch (Exception ex) { saved.completeExceptionally(ex); }
            });
            worker.shutdown();
        }
        return saved;
    }
}
