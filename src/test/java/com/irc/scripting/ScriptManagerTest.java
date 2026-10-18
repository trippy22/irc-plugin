package com.irc.scripting;

import org.junit.After;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class ScriptManagerTest {
    private static final class MemoryStore implements ScriptManager.Store {
        final Map<String, String> files = new java.util.concurrent.ConcurrentHashMap<>();
        volatile boolean failWrites;
        public String read(String name) { return files.get(name); }
        public void write(String name, String value) throws IOException { if (failWrites) throw new IOException("disk full"); files.put(name, value); }
        public void delete(String name) { files.remove(name); }
        public String path(String name) { return "managed/" + name; }
    }
    private final MemoryStore store = new MemoryStore();
    private final List<String> output = Collections.synchronizedList(new ArrayList<>());
    private final List<ScriptManager> managers = new ArrayList<>();
    private final ScriptContext context = new ScriptContext("network-a", "#test", "Alice", "me", "!ping");
    private final ScriptManager.Output sink = new ScriptManager.Output() {
        public void echo(ScriptContext c, String text) { output.add(c.network + " echo " + text); }
        public void send(ScriptContext c, String target, String text, boolean notice) { output.add(c.network + " " + target + " " + text); }
    };

    private ScriptManager manager() throws Exception {
        ScriptManager manager = new ScriptManager(store, sink); managers.add(manager); await(manager.ready()); return manager;
    }
    @After public void shutdown() throws Exception { for (ScriptManager manager : managers) await(manager.close(null, null)); }
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    private static void fails(CompletableFuture<?> future, String message) throws Exception {
        try { await(future); fail("Expected " + message); }
        catch (ExecutionException ex) { assertTrue(ScriptManager.message(ex), ScriptManager.message(ex).contains(message)); }
    }

    @Test public void disabledByDefaultAndReloadFailureKeepsRunningVersionAndDisk() throws Exception {
        ScriptManager m = manager();
        String old = "alias hi { return old }";
        await(m.add("hello", old));
        assertFalse(m.snapshot().get(0).enabled);
        fails(m.run("hi", "", context), "No enabled script");
        await(m.enable("hello", true));
        fails(m.save("hello", "alias hi { raw forbidden }", old, true), "Unsupported command");
        assertEquals(old, store.read("hello.mrc"));
        assertEquals("old", await(m.run("hi", "", context)));
        assertTrue(m.snapshot().get(0).running);
        assertTrue(m.snapshot().get(0).status.contains("Line 1"));
        await(m.save("hello", "alias hi { return new }", old, false));
        assertEquals("old", await(m.run("hi", "", context)));
        await(m.reload("hello"));
        assertEquals("new", await(m.run("hi", "", context)));
    }

    @Test public void remembersEnabledScriptsAndGlobalsAcrossLifecycles() throws Exception {
        ScriptManager first = manager();
        await(first.add("vars", "alias put { set %word = $1- }\nalias get { return %word }"));
        await(first.enable("vars", true));
        await(first.run("put", "persistent text", context));
        await(first.close(null, null));
        ScriptManager second = manager();
        assertTrue(second.snapshot().get(0).enabled);
        assertEquals("persistent text", await(second.run("get", "", context)));
        await(second.enable("vars", false));
        await(second.close(null, null));
        ScriptManager third = manager();
        assertFalse(third.snapshot().get(0).running);
    }

    @Test public void removalKeepsFileAndStateWhileDeleteRemovesManagedData() throws Exception {
        ScriptManager m = manager();
        await(m.add("a", "alias put { set %v = saved }\nalias get { return %v }"));
        await(m.enable("a", true)); await(m.run("put", "", context));
        await(m.remove("a", false)); assertNotNull(store.read("a.mrc")); assertTrue(m.snapshot().isEmpty());
        await(m.add("a", null)); await(m.enable("a", true));
        assertEquals("saved", await(m.run("get", "", context)));
        await(m.remove("a", true)); assertNull(store.read("a.mrc")); assertNull(store.read("a.mrc.state"));
    }

    @Test public void duplicateAliasesAndPathsAreRejectedWithoutChangingOtherScripts() throws Exception {
        ScriptManager m = manager();
        await(m.add("one", "alias hi { return one }")); await(m.enable("one", true));
        await(m.add("two", "alias hi { return two }"));
        fails(m.enable("two", true), "already provided");
        assertFalse(m.snapshot().get(1).enabled);
        assertEquals("one", await(m.run("hi", "", context)));
        for (String name : new String[]{"../escape", "C:\\secret", "con", "nested/file", ""}) fails(m.add(name, ""), "script name");
    }

    @Test public void preservesExternalEditsAndHandlesDiskFailureTransactionally() throws Exception {
        ScriptManager m = manager(); String source = "alias hi { return old }";
        await(m.add("one", source)); await(m.enable("one", true));
        store.files.put("one.mrc", "alias hi { return external }");
        fails(m.save("one", "alias hi { return edited }", source, true), "changed on disk");
        assertEquals("old", await(m.run("hi", "", context)));
        store.failWrites = true;
        fails(m.enable("one", false), "disk full");
        assertFalse(m.snapshot().get(0).enabled);
        fails(m.run("hi", "", context), "No enabled script");
        store.failWrites = false;
    }

    @Test public void restoresDraftWithoutExecutingIt() throws Exception {
        ScriptManager first = manager(); String source = "alias hi { return saved }";
        await(first.add("one", source)); await(first.enable("one", true));
        await(first.close("one", "alias hi { return draft }"));
        ScriptManager second = manager();
        assertEquals("saved", await(second.run("hi", "", context)));
        ScriptManager.Document doc = await(second.document("one"));
        assertEquals(source, doc.disk); assertTrue(doc.source.contains("draft"));
        await(second.save("one", doc.source, doc.disk, true)); assertNull(store.read("one.mrc.draft"));
        assertEquals("draft", await(second.run("hi", "", context)));
    }

    @Test public void eventsCarryNetworkContextAndRateLimitAllScriptsTogether() throws Exception {
        ScriptManager m = manager();
        await(m.add("one", "on *:TEXT:!ping:#:{ msg $chan pong }\nalias say { echo hi }"));
        await(m.enable("one", true)); await(m.permissions("one", true, false)); m.text(context);
        await(m.run("say", "", context));
        assertEquals("network-a #test pong", output.get(0));
        await(m.run("say", "", context)); await(m.run("say", "", context));
        fails(m.run("say", "", context), "rate limit");
        assertEquals(4, output.size());
    }

    @Test public void disableReloadAndShutdownCancelTimers() throws Exception {
        String source = "alias later { echo fired }\nalias start { timerreminder 1 1 later }";
        ScriptManager disabled = manager(); await(disabled.add("one", source)); await(disabled.enable("one", true));
        await(disabled.run("start", "", context)); await(disabled.enable("one", false));
        ScriptManager reloaded = manager(); await(reloaded.enable("one", true));
        await(reloaded.run("start", "", context)); await(reloaded.reload("one"));
        ScriptManager stopped = manager(); await(stopped.run("start", "", context)); await(stopped.close(null, null));
        CompletableFuture<Void> delay = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(1300, TimeUnit.MILLISECONDS).execute(() -> delay.complete(null));
        await(delay); assertTrue(output.isEmpty());
        fails(stopped.run("later", "", context), "stopped");
    }

    @Test public void corruptManifestIsNotOverwrittenBySubsequentCommands() throws Exception {
        store.files.put("scripts.index", "../escape\t1");
        ScriptManager m = new ScriptManager(store, sink); managers.add(m);
        fails(m.ready(), "script name"); fails(m.add("safe", ""), "script name");
        assertEquals("../escape\t1", store.files.get("scripts.index"));
    }

    @Test public void stateEncodingPreservesUnicodeAndRejectsInvalidNames() {
        Map<String, String> vars = new LinkedHashMap<>(); vars.put("hello", "Unicode ☃\nline\ttab");
        assertEquals(vars, ScriptManager.readGlobals(ScriptManager.writeGlobals(vars)));
        try { ScriptManager.readGlobals("../x\tYQ=="); fail(); } catch (IllegalArgumentException expected) { }
    }

    @Test public void repliesRequirePermissionAndCannotChangeDestination() throws Exception {
        ScriptManager m = manager();
        await(m.add("one", "alias reply { msg $1 $2- }")); await(m.enable("one", true));
        fails(m.run("reply", "#test hello", context), "not permitted");
        assertTrue(output.isEmpty());
        await(m.permissions("one", true, false));
        fails(m.run("reply", "#elsewhere stolen", context), "originating conversation");
        await(m.run("reply", "#test hello", context));
        assertEquals(Collections.singletonList("network-a #test hello"), output);
        fails(m.run("reply", "#test hello", context), "Duplicate");
        assertFalse(m.snapshot().get(0).running);
        assertTrue(m.snapshot().get(0).status.contains("Automatically disabled"));
        await(m.close(null, null));
        assertFalse(manager().snapshot().get(0).enabled);
    }

    @Test public void privateEventsAreHiddenAndRepliesNeedBothPermissions() throws Exception {
        ScriptContext pm = new ScriptContext("network-a", "Alice", "Alice", "me", "hi");
        ScriptManager m = manager();
        await(m.add("one", "on *:TEXT:*:?:{ set %seen yes }\nalias seen { return %seen }\nalias reply { msg $nick hi }"));
        await(m.enable("one", true)); m.text(pm);
        assertEquals("", await(m.run("seen", "", context)));
        await(m.permissions("one", true, false));
        fails(m.run("reply", "", pm), "Private conversations");
        await(m.permissions("one", false, true)); m.text(pm);
        assertEquals("yes", await(m.run("seen", "", context)));
        fails(m.run("reply", "", pm), "replies are not permitted");
        await(m.permissions("one", true, true)); await(m.run("reply", "", pm));
        assertEquals(Collections.singletonList("network-a Alice hi"), output);
    }

    @Test public void serviceCommandsAndOversizedUtf8RepliesAreBlocked() throws Exception {
        ScriptManager m = manager();
        await(m.add("one", "alias reply { msg $1 $2- }")); await(m.enable("one", true));
        await(m.permissions("one", true, true));
        fails(m.run("reply", "NickServ IDENTIFY secret", new ScriptContext("a", "NickServ", "NickServ", "me", "")), "services");
        fails(m.run("reply", "#test " + "☃".repeat(101), context), "300 UTF-8 bytes");
        assertTrue(output.isEmpty());
    }

    @Test public void permissionsPersistButReloadCannotGrantThemAndOldIndexesDefaultToNone() throws Exception {
        store.files.put("scripts.index", "one.mrc\t1\n");
        store.files.put("one.mrc", "alias hi return ok");
        ScriptManager m = manager();
        assertFalse(m.snapshot().get(0).replies); assertFalse(m.snapshot().get(0).privateMessages);
        await(m.permissions("one", true, false)); await(m.reload("one"));
        assertTrue(m.snapshot().get(0).replies); assertFalse(m.snapshot().get(0).privateMessages);
        await(m.close(null, null));
        ScriptManager restored = manager();
        assertTrue(restored.snapshot().get(0).replies); assertFalse(restored.snapshot().get(0).privateMessages);
        await(restored.remove("one", false)); await(restored.add("one", null));
        assertFalse(restored.snapshot().get(0).replies);
    }

    @Test public void malformedPermissionFlagsFailClosedWithoutRewritingIndex() throws Exception {
        String manifest = "one.mrc\t1\ttrue\t0\n";
        store.files.put("scripts.index", manifest); store.files.put("one.mrc", "alias hi return ok");
        ScriptManager m = new ScriptManager(store, sink); managers.add(m);
        fails(m.ready(), "Invalid scripts.index"); assertTrue(m.snapshot().isEmpty());
        fails(m.enable("one", true), "Invalid scripts.index");
        assertEquals(manifest, store.read("scripts.index"));
    }

    @Test public void permissionWriteFailureCannotGrantOrUndoRevocation() throws Exception {
        ScriptManager m = manager();
        await(m.add("one", "alias hi return ok")); await(m.enable("one", true));
        store.failWrites = true;
        fails(m.permissions("one", true, true), "disk full");
        assertFalse(m.snapshot().get(0).replies); assertFalse(m.snapshot().get(0).privateMessages);
        store.failWrites = false; await(m.permissions("one", true, true)); store.failWrites = true;
        fails(m.permissions("one", false, false), "disk full");
        assertFalse(m.snapshot().get(0).replies); assertFalse(m.snapshot().get(0).privateMessages);
        store.failWrites = false;
    }

    @Test public void rapidEventsAreDroppedAndLocalAliasesCannotBeInvokedExternally() throws Exception {
        ScriptManager m = manager();
        await(m.add("one", "alias -l hidden return secret\nalias get return %count\non *:TEXT:*:#:{ inc %count }"));
        await(m.enable("one", true));
        for (int i = 0; i < 20; i++) m.text(context);
        assertEquals("1", await(m.run("get", "", context)));
        fails(m.run("hidden", "", context), "No enabled script");
    }

    @Test public void revokingPermissionsCancelsExistingTimers() throws Exception {
        ScriptManager m = manager();
        await(m.add("one", "alias start { timerlater 2 1 echo fired }")); await(m.enable("one", true));
        await(m.permissions("one", true, true)); await(m.run("start", "", context));
        await(m.permissions("one", false, false));
        CompletableFuture<Void> delay = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(1250, TimeUnit.MILLISECONDS).execute(() -> delay.complete(null));
        await(delay); assertTrue(output.isEmpty());
    }

    @Test public void repeatingTimersAreFiniteAndCancelThemselves() throws Exception {
        ScriptManager m = manager();
        await(m.add("one", "alias tick { inc %ticks | timerrepeat off }\nalias start { timerrepeat 20 1 tick }\nalias get return %ticks"));
        await(m.enable("one", true)); await(m.run("start", "", context));
        CompletableFuture<Void> delay = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(2250, TimeUnit.MILLISECONDS).execute(() -> delay.complete(null));
        await(delay); assertEquals("1", await(m.run("get", "", context)));
    }
}
