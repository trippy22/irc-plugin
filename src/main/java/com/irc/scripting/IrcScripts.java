package com.irc.scripting;

import net.runelite.client.util.Filepath;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** Plugin-facing service. Parser, storage, permissions and editor implementation stay package-private. */
public final class IrcScripts {
    public interface Directory { Filepath get() throws IOException; }
    public interface Output {
        void echo(ScriptContext context, String text);
        void send(ScriptContext context, String target, String text, boolean notice);
    }
    private final ScriptManager manager;
    private volatile ScriptsDialog dialog;
    private volatile boolean closed;

    public IrcScripts(Directory directory, Output output, CompletableFuture<Void> predecessor) {
        manager = new ScriptManager(new ScriptFiles(directory::get), new ScriptManager.Output() {
            public void echo(ScriptContext context, String text) { output.echo(context, text); }
            public void send(ScriptContext context, String target, String text, boolean notice) { output.send(context, target, text, notice); }
        }, predecessor);
    }
    public CompletableFuture<Void> ready() { return manager.ready(); }
    public List<String> list() {
        return manager.snapshot().stream().map(v -> (v.enabled ? "[enabled] " : "[disabled] ") + v.name + " — " + v.status).collect(Collectors.toList());
    }
    public CompletableFuture<Void> load(String name) { return manager.add(name, null); }
    public CompletableFuture<Void> reload(String name) { return manager.reload(name); }
    public CompletableFuture<Void> enable(String name, boolean enabled) { return manager.enable(name, enabled); }
    public CompletableFuture<Void> remove(String name) { return manager.remove(name, false); }
    public CompletableFuture<String> run(String alias, String args, ScriptContext context) { return manager.run(alias, args, context); }
    public void text(ScriptContext context) { manager.text(context); }
    public void open(Component parent) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Open scripts on the EDT");
        if (closed) return;
        if (dialog == null) { dialog = new ScriptsDialog(manager); dialog.setLocationRelativeTo(parent); }
        if (closed) { dialog.shutdown(); return; }
        dialog.setVisible(true);
    }
    public CompletableFuture<Void> close() {
        closed = true;
        ScriptsDialog current = dialog;
        ScriptsDialog.Draft draft = current == null ? new ScriptsDialog.Draft(null, null) : current.draft();
        CompletableFuture<Void> result = manager.close(draft.name, draft.source);
        // Resolve on the EDT too, in case open() was still constructing the dialog at close().
        SwingUtilities.invokeLater(() -> { if (dialog != null) dialog.shutdown(); });
        return result;
    }
    public static String message(Throwable error) { return ScriptManager.message(error); }
}
