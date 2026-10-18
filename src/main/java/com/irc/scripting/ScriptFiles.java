package com.irc.scripting;

import net.runelite.client.util.Filepath;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** All production disk access is through RuneLite's confined Filepath API. Called on the worker. */
final class ScriptFiles implements ScriptManager.Store {
    interface Directory { Filepath get() throws IOException; }
    private final Directory directory;
    private Filepath root;

    ScriptFiles(Directory directory) { this.directory = directory; }

    private Filepath root() throws IOException {
        if (root == null) {
            root = directory.get().joinSegment("scripts").rooted();
            root.createDirectories();
        }
        return root;
    }

    @Override public String read(String name) throws IOException {
        Filepath file = root().joinSegment(name);
        return file.exists() ? readFile(file) : null;
    }

    static String readFile(Filepath file) throws IOException {
        // Read with a hard cap too: a file may grow after size() is checked.
        if (file.size() > 1024 * 1024) throw new IOException("File exceeds 1 MiB");
        try (Reader reader = file.openReader()) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[4096]; int count;
            while ((count = reader.read(buffer)) != -1) {
                if (result.length() + count > 1024 * 1024) throw new IOException("File exceeds 1 MiB");
                result.append(buffer, 0, count);
            }
            return result.toString();
        }
    }

    @Override public void write(String name, String source) throws IOException {
        Filepath destination = root().joinSegment(name);
        Filepath temp = root().createTempFile("script-", ".tmp");
        try {
            temp.write(source, StandardOpenOption.TRUNCATE_EXISTING);
            try { temp.moveTo(destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { temp.moveTo(destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { temp.deleteIfExists(); }
    }

    @Override public void delete(String name) throws IOException { root().joinSegment(name).deleteIfExists(); }
    @Override public String path(String name) throws IOException { return root().joinSegment(name).toString(); }
}
