package com.irc;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Moves the live chat controls between hosts. All methods run on the Swing EDT. */
final class IrcPanelWindow {
    private final JPanel dockHost;
    private final JPanel content;
    private final Runnable beforeMove;
    private final Runnable hidePreviews;
    private final Runnable dockRequested;
    /** The saved {@link PopOutGeometry}, serialized. */
    private final Supplier<String> loadGeometry;
    private final Consumer<String> saveGeometry;
    private final Supplier<Window> findMainWindow;
    private JFrame frame;
    private IrcEmbeddedDock embeddedDock;
    private boolean alwaysOnTop;
    private Window mainWindow;
    private boolean bottomDocked;
    private boolean hiddenWithOwner;
    private int dockHeight = BottomDockGeometry.DEFAULT_HEIGHT;
    private Rectangle dockBounds;
    private Rectangle placedBounds;
    private Consumer<Boolean> dockingChanged = docked -> { };
    private final Timer snapTimer = new Timer(180, e -> settleGeometry());
    private final ComponentAdapter mainGeometryListener = new ComponentAdapter() {
        @Override public void componentMoved(ComponentEvent e) { followMainWindow(); }
        @Override public void componentResized(ComponentEvent e) { followMainWindow(); }
        @Override public void componentShown(ComponentEvent e) { followMainWindow(); }
        @Override public void componentHidden(ComponentEvent e) { followMainWindow(); }
    };
    private final WindowAdapter mainStateListener = new WindowAdapter() {
        @Override public void windowIconified(WindowEvent e) { followMainWindow(); }
        @Override public void windowDeiconified(WindowEvent e) { followMainWindow(); }
    };
    /** The pop-out's bounds when last neither maximized nor minimized. */
    private Rectangle normalBounds;
    /** Saves once a move or resize settles, rather than on every step of a drag. */
    private final Timer saveTimer = new Timer(500, e -> saveGeometry());
    private final ComponentAdapter frameGeometryListener = new ComponentAdapter() {
        @Override
        public void componentMoved(ComponentEvent e) { geometryChanged(); }

        @Override
        public void componentResized(ComponentEvent e) { geometryChanged(); }
    };
    private Window observedWindow;
    private final WindowAdapter windowListener = new WindowAdapter() {
        @Override
        public void windowLostFocus(WindowEvent e) { hidePreviews.run(); }

        @Override
        public void windowDeactivated(WindowEvent e) { hidePreviews.run(); }

        @Override
        public void windowIconified(WindowEvent e) { hidePreviews.run(); }
    };
    private final ComponentAdapter componentListener = new ComponentAdapter() {
        @Override
        public void componentMoved(ComponentEvent e) { hidePreviews.run(); }

        @Override
        public void componentResized(ComponentEvent e) { hidePreviews.run(); }
    };
    private final HierarchyListener hierarchyListener = this::hierarchyChanged;

    private void hierarchyChanged(HierarchyEvent e) {
        if ((e.getChangeFlags() & (HierarchyEvent.PARENT_CHANGED | HierarchyEvent.SHOWING_CHANGED)) != 0) {
            observeWindow(SwingUtilities.getWindowAncestor(content));
            if (!content.isShowing()) {
                hidePreviews.run();
            }
        }
    }

    /** Remembers the pop-out's geometry only while this object lives. */
    IrcPanelWindow(JPanel dockHost, JPanel content, Runnable beforeMove,
                   Runnable hidePreviews, Runnable dockRequested) {
        this(dockHost, content, beforeMove, hidePreviews, dockRequested, new AtomicReference<>());
    }

    private IrcPanelWindow(JPanel dockHost, JPanel content, Runnable beforeMove,
                           Runnable hidePreviews, Runnable dockRequested, AtomicReference<String> memory) {
        this(dockHost, content, beforeMove, hidePreviews, dockRequested, memory::get, memory::set);
    }

    IrcPanelWindow(JPanel dockHost, JPanel content, Runnable beforeMove, Runnable hidePreviews,
                   Runnable dockRequested, Supplier<String> loadGeometry, Consumer<String> saveGeometry) {
        this(dockHost, content, beforeMove, hidePreviews, dockRequested, loadGeometry, saveGeometry,
                () -> SwingUtilities.getWindowAncestor(dockHost));
    }

    IrcPanelWindow(JPanel dockHost, JPanel content, Runnable beforeMove, Runnable hidePreviews,
                   Runnable dockRequested, Supplier<String> loadGeometry, Consumer<String> saveGeometry,
                   Supplier<Window> findMainWindow) {
        this.loadGeometry = loadGeometry;
        this.saveGeometry = saveGeometry;
        this.findMainWindow = findMainWindow;
        saveTimer.setRepeats(false);
        snapTimer.setRepeats(false);
        this.dockHost = dockHost;
        this.content = content;
        this.beforeMove = beforeMove;
        this.hidePreviews = hidePreviews;
        this.dockRequested = dockRequested;
        content.addHierarchyListener(hierarchyListener);
        observeWindow(SwingUtilities.getWindowAncestor(content));
    }

    void setDetached(boolean detached, boolean alwaysOnTop) {
        assert SwingUtilities.isEventDispatchThread();
        this.alwaysOnTop = alwaysOnTop;
        if (detached) {
            if (embeddedDock != null) return;
            if (frame == null) {
                beforeMove.run();
                mainWindow = findMainWindow.get();
                Window owner = mainWindow != null ? mainWindow : SwingUtilities.getWindowAncestor(content);
                frame = new JFrame("Global Chat (IRC)");
                frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                frame.setMinimumSize(new Dimension(640, BottomDockGeometry.MIN_HEIGHT));
                frame.setSize(960, 620);
                if (owner != null) {
                    frame.setIconImages(owner.getIconImages());
                }
                frame.setLocationRelativeTo(owner);
                // Reopen where it was last time, provided its title bar is on a screen still there.
                PopOutGeometry saved = PopOutGeometry.parse(loadGeometry.get());
                boolean restore = saved != null && saved.titleBarVisible(screens());
                if (restore) frame.setBounds(saved.bounds);
                normalBounds = frame.getBounds();
                placedBounds = frame.getBounds();
                bottomDocked = saved != null && !saved.embedded && saved.dockHeight > 0 && mainWindow != null;
                if (saved != null && saved.dockHeight > 0) dockHeight = saved.dockHeight;
                if (!bottomDocked && restore && saved.maximized) frame.setExtendedState(Frame.MAXIMIZED_BOTH);
                frame.addWindowListener(new WindowAdapter() {
                    @Override
                    public void windowClosing(WindowEvent e) { dockRequested.run(); }
                });
                frame.addWindowStateListener(e -> geometryChanged());
                frame.addComponentListener(frameGeometryListener);
                frame.add(content, BorderLayout.CENTER);
                dockHost.revalidate();
                dockHost.repaint();
                frame.setAlwaysOnTop(alwaysOnTop);
                if (saved != null && saved.embedded && canEmbed()) {
                    embedInMainWindow();
                    return;
                }
                frame.setVisible(true);
                if (mainWindow != null) {
                    mainWindow.addComponentListener(mainGeometryListener);
                    mainWindow.addWindowListener(mainStateListener);
                }
                dockingChanged.accept(bottomDocked);
                followMainWindow();
            } else {
                frame.setAlwaysOnTop(alwaysOnTop);
            }
        } else if (frame != null || embeddedDock != null) {
            beforeMove.run();
            snapTimer.stop();
            saveTimer.stop();
            saveGeometry();
            if (embeddedDock != null) {
                embeddedDock.remove();
                embeddedDock = null;
            }
            if (mainWindow != null) {
                mainWindow.removeComponentListener(mainGeometryListener);
                mainWindow.removeWindowListener(mainStateListener);
                mainWindow = null;
            }
            dockHost.add(content, BorderLayout.CENTER);
            if (frame != null) frame.dispose();
            frame = null;
            bottomDocked = false;
            hiddenWithOwner = false;
            dockBounds = null;
            dockHost.revalidate();
            dockHost.repaint();
        }
    }

    private void geometryChanged() {
        if (frame == null) return;
        // Maximized or minimized bounds aren't worth keeping: un-maximizing goes back to these.
        if (!bottomDocked && frame.getExtendedState() == Frame.NORMAL) normalBounds = frame.getBounds();
        if (!frame.getBounds().equals(placedBounds)) snapTimer.restart();
        saveTimer.restart();
    }

    void setDockingChanged(Consumer<Boolean> listener) {
        dockingChanged = listener;
    }

    boolean canAttachBelow() {
        return mainWindow != null;
    }

    boolean isBottomDocked() {
        return bottomDocked;
    }

    boolean isEmbedded() { return embeddedDock != null; }

    boolean canEmbed() { return mainWindow instanceof JFrame; }

    void toggleEmbedded() {
        if (isEmbedded()) popOut();
        else embedInMainWindow();
    }

    void embedInMainWindow() {
        if (embeddedDock != null || frame == null || !canEmbed()) return;
        beforeMove.run();
        snapTimer.stop();
        saveTimer.stop();
        if (!bottomDocked && frame.getExtendedState() == Frame.NORMAL) normalBounds = frame.getBounds();
        mainWindow.removeComponentListener(mainGeometryListener);
        mainWindow.removeWindowListener(mainStateListener);
        bottomDocked = false;
        hiddenWithOwner = false;
        dockBounds = null;
        embeddedDock = new IrcEmbeddedDock((JFrame) mainWindow, content, dockHeight, height -> {
            dockHeight = height;
            saveTimer.restart();
        });
        frame.dispose();
        frame = null;
        dockingChanged.accept(false);
        saveGeometry();
    }

    /** Explicit pop-out requests leave the combined window; repeated requests focus an existing pop-out. */
    void popOut() {
        if (embeddedDock != null) {
            beforeMove.run();
            dockHeight = embeddedDock.getHeight();
            embeddedDock.remove();
            embeddedDock = null;
            saveGeometry.accept(new PopOutGeometry(normalBounds, false).serialize());
            setDetached(true, alwaysOnTop);
        }
        toFront();
    }

    void toggleBottomDock() {
        if (bottomDocked) releaseBottomDock(true);
        else attachBelow();
    }

    void attachBelow() {
        if (frame == null || mainWindow == null) return;
        if (!bottomDocked && frame.getExtendedState() == Frame.NORMAL) normalBounds = frame.getBounds();
        bottomDocked = true;
        frame.setExtendedState(Frame.NORMAL);
        dockingChanged.accept(true);
        followMainWindow();
        saveTimer.restart();
    }

    private void releaseBottomDock(boolean restoreFloatingBounds) {
        bottomDocked = false;
        dockBounds = null;
        if (hiddenWithOwner) frame.setVisible(true);
        hiddenWithOwner = false;
        dockingChanged.accept(false);
        placedBounds = restoreFloatingBounds && normalBounds != null ? new Rectangle(normalBounds) : frame.getBounds();
        snapTimer.stop();
        if (restoreFloatingBounds && normalBounds != null) frame.setBounds(normalBounds);
        else if (frame.getExtendedState() == Frame.NORMAL) normalBounds = frame.getBounds();
        saveTimer.restart();
    }

    /** Native title-bar drags arrive as component events; wait for them to settle before snapping. */
    private void settleGeometry() {
        if (frame == null || mainWindow == null || hiddenWithOwner) return;
        if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) return;
        Rectangle bounds = frame.getBounds();
        if (bottomDocked) {
            if (frame.getExtendedState() != Frame.NORMAL) {
                releaseBottomDock(false);
            } else if (dockBounds != null && !bounds.equals(dockBounds)) {
                // Resizing the lower edge adjusts dock height. Moving the title bar releases it.
                if (bounds.x == dockBounds.x && bounds.y == dockBounds.y) {
                    dockHeight = bounds.height;
                    followMainWindow();
                } else {
                    releaseBottomDock(false);
                }
            }
        } else if (frame.getExtendedState() == Frame.NORMAL && mainWindow.isShowing()
                && BottomDockGeometry.nearBottom(bounds, mainWindow.getBounds())) {
            attachBelow();
        }
    }

    private void followMainWindow() {
        if (!bottomDocked || frame == null || mainWindow == null) return;
        boolean visible = mainWindow.isShowing()
                && (!(mainWindow instanceof Frame) || (((Frame) mainWindow).getExtendedState() & Frame.ICONIFIED) == 0);
        if (!visible) {
            hiddenWithOwner = true;
            frame.setVisible(false);
            return;
        }
        GraphicsConfiguration gc = mainWindow.getGraphicsConfiguration();
        Rectangle workArea = new Rectangle(gc.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        workArea.x += insets.left;
        workArea.y += insets.top;
        workArea.width -= insets.left + insets.right;
        workArea.height -= insets.top + insets.bottom;
        dockBounds = BottomDockGeometry.below(mainWindow.getBounds(), workArea, dockHeight);
        placedBounds = new Rectangle(dockBounds);
        if (!frame.getBounds().equals(dockBounds)) frame.setBounds(dockBounds);
        if (hiddenWithOwner) {
            hiddenWithOwner = false;
            frame.setVisible(true);
        }
    }

    private void saveGeometry() {
        if (embeddedDock != null) {
            saveGeometry.accept(new PopOutGeometry(normalBounds, false, embeddedDock.getHeight(), true).serialize());
            return;
        }
        if (frame == null || normalBounds == null) return;
        if (!bottomDocked && frame.getExtendedState() == Frame.NORMAL) normalBounds = frame.getBounds();
        boolean maximized = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH;
        saveGeometry.accept(new PopOutGeometry(normalBounds, maximized, bottomDocked ? dockHeight : 0).serialize());
    }

    private static List<Rectangle> screens() {
        List<Rectangle> screens = new ArrayList<>();
        for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            screens.add(screen.getDefaultConfiguration().getBounds());
        }
        return screens;
    }

    void toFront() {
        assert SwingUtilities.isEventDispatchThread();
        if (embeddedDock != null) {
            Frame owner = (Frame) mainWindow;
            owner.setExtendedState(owner.getExtendedState() & ~Frame.ICONIFIED);
            owner.toFront();
            return;
        }
        if (frame == null) return;
        if (bottomDocked && mainWindow instanceof Frame) {
            Frame owner = (Frame) mainWindow;
            owner.setExtendedState(owner.getExtendedState() & ~Frame.ICONIFIED);
            followMainWindow();
        }
        if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) {
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        }
        frame.toFront();
        frame.requestFocus();
    }

    private void observeWindow(Window window) {
        if (observedWindow == window) return;
        if (observedWindow != null) {
            observedWindow.removeWindowFocusListener(windowListener);
            observedWindow.removeWindowListener(windowListener);
            observedWindow.removeComponentListener(componentListener);
        }
        observedWindow = window;
        if (window != null) {
            window.addWindowFocusListener(windowListener);
            window.addWindowListener(windowListener);
            window.addComponentListener(componentListener);
        }
    }

    void shutdown() {
        assert SwingUtilities.isEventDispatchThread();
        setDetached(false, false);
        content.removeHierarchyListener(hierarchyListener);
        observeWindow(null);
    }
}
