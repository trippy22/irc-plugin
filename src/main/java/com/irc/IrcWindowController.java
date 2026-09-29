package com.irc;

import javax.swing.SwingUtilities;
import java.util.function.BiConsumer;

/** One transition path for config changes, Dock, and the popout's X button. */
final class IrcWindowController {
    enum Host { DOCKED, DETACHED, CLOSED }
    private Host host = Host.DOCKED;
    private boolean alwaysOnTop;
    private final BiConsumer<Boolean, Boolean> render;
    private final Runnable persistDock;

    IrcWindowController(BiConsumer<Boolean, Boolean> render, Runnable persistDock) {
        this.render = render; this.persistDock = persistDock;
    }
    Host host() { return host; }
    void configure(boolean detached, boolean top) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Window changes require EDT");
        if (host == Host.CLOSED) return;
        host = detached ? Host.DETACHED : Host.DOCKED;
        alwaysOnTop = top;
        render.accept(detached, top);
    }
    void dock() {
        if (host == Host.CLOSED) return;
        configure(false, alwaysOnTop);
        persistDock.run();
    }
    void close() { configure(false, false); host = Host.CLOSED; }
}
