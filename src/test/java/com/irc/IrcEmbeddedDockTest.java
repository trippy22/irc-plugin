package com.irc;

import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.ContainableFrame;
import net.runelite.client.ui.laf.RuneLiteLAF;
import org.junit.Before;
import org.junit.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class IrcEmbeddedDockTest {
    @Before public void desktop() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
    }

    @Test public void sharesTheRuneLiteWindowWithoutRecreatingTheCanvasAndRestoresLayout() throws Exception {
        AtomicReference<Fixture> ref = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = new Fixture();
                ref.set(f);
                f.host.setDetached(true, false);
                JFrame floating = (JFrame) SwingUtilities.getWindowAncestor(f.chat);
                f.host.attachBelow();
                int originalGameHeight = f.game.getHeight();
                f.host.embedInMainWindow();
                f.main.validate();
                assertTrue(f.host.isEmbedded());
                assertFalse(f.host.isBottomDocked());
                assertSame(f.main, SwingUtilities.getWindowAncestor(f.chat));
                assertFalse("the separate native window is disposed", floating.isDisplayable());
                f.checkCanvas();
                assertNoOverlap(f);
                assertEquals("combining adds chat without inflating the game area", originalGameHeight, f.game.getHeight());
                assertEquals("draft", f.input.getText());
                f.main.setSize(f.main.getWidth() + 40, f.main.getHeight() + 35);
                f.main.validate();
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = ref.get();
                assertNoOverlap(f);
                assertSame("RuneLite's custom content layout stays installed", f.gameLayout, f.game.getLayout());
                // RuneLite resizes its own frame when opening sidebars. Exercise that real code too.
                f.sidebar.setPreferredSize(new Dimension(240, 503));
                f.game.revalidate();
                f.main.validate();
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = ref.get();
                assertNoOverlap(f);
                f.sidebar.setVisible(false);
                f.game.revalidate();
                f.main.validate();
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = ref.get();
                assertNoOverlap(f);
                f.host.setDetached(true, true); // Updating always-on-top must not reopen a pop-out.
                assertSame(f.main, SwingUtilities.getWindowAncestor(f.chat));
                f.host.shutdown();
                assertSame(f.rootLayout, f.main.getRootPane().getLayout());
                assertSame(f.game, f.main.getContentPane());
                assertSame(f.dockHost, f.chat.getParent());
                f.checkCanvas();
                assertNull(find(f.main.getRootPane(), "ircEmbeddedDock"));
                assertEquals(f.focusListeners, f.main.getWindowFocusListeners().length);
            });
        } finally { SwingUtilities.invokeAndWait(() -> { if (ref.get() != null) ref.get().close(); }); }
    }

    private static void settle() throws Exception {
        Thread.sleep(150);
        SwingUtilities.invokeAndWait(() -> { });
    }

    @Test public void dividerHeightAndCombinedModeSurviveReopeningAndExplicitPopOutRestoresBounds() throws Exception {
        AtomicReference<Fixture> ref = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = new Fixture();
                ref.set(f);
                f.host.setDetached(true, false);
                Window floating = SwingUtilities.getWindowAncestor(f.chat);
                floating.setBounds(110, 110, 900, 540);
                f.host.embedInMainWindow();
                f.main.setSize(f.main.getWidth(), 1000);
                f.main.validate();
                Component divider = find(f.main.getRootPane(), "ircBottomDivider");
                assertNotNull(divider);
                int oldHeight = f.chat.getHeight();
                Point screen = divider.getLocationOnScreen();
                divider.dispatchEvent(new MouseEvent(divider, MouseEvent.MOUSE_PRESSED, 1, 0,
                        4, 4, screen.x + 4, screen.y + 4, 1, false, MouseEvent.BUTTON1));
                divider.dispatchEvent(new MouseEvent(divider, MouseEvent.MOUSE_DRAGGED, 2, MouseEvent.BUTTON1_DOWN_MASK,
                        4, -26, screen.x + 4, screen.y - 26, 0, false, MouseEvent.NOBUTTON));
                f.main.validate();
                assertEquals(oldHeight + 30, f.chat.getHeight());
                f.host.setDetached(false, false);
                PopOutGeometry saved = PopOutGeometry.parse(f.saved.get());
                assertTrue(saved.embedded);
                assertEquals(oldHeight + 30, saved.dockHeight);
                assertEquals(new Rectangle(110, 110, 900, 540), saved.bounds);
                f.host.setDetached(true, false);
                assertTrue(f.host.isEmbedded());
                assertSame(f.main, SwingUtilities.getWindowAncestor(f.chat));
                f.host.popOut();
                assertFalse(f.host.isEmbedded());
                assertNotSame(f.main, SwingUtilities.getWindowAncestor(f.chat));
                assertEquals(saved.bounds, SwingUtilities.getWindowAncestor(f.chat).getBounds());
                assertSame(f.rootLayout, f.main.getRootPane().getLayout());
                assertFalse(PopOutGeometry.parse(f.saved.get()).embedded);
                f.checkCanvas();
            });
        } finally { SwingUtilities.invokeAndWait(() -> { if (ref.get() != null) ref.get().close(); }); }
    }

    @Test public void geometryRecognizesBothOldAttachmentAndNewCombinedMode() {
        PopOutGeometry saved = PopOutGeometry.parse("100,100,900,540,embedded,260");
        assertNotNull(saved);
        assertTrue(saved.embedded);
        assertEquals(260, saved.dockHeight);
        assertEquals("100,100,900,540,embedded,260", saved.serialize());
        assertFalse(PopOutGeometry.parse("100,100,900,540,bottom,260").embedded);
        assertNull(PopOutGeometry.parse("100,100,900,540,embedded,0"));
    }

    private static void assertNoOverlap(Fixture f) {
        JPanel pane = (JPanel) find(f.main.getRootPane(), "ircEmbeddedDock");
        assertNotNull(pane);
        assertEquals(f.game.getY() + f.game.getHeight(), pane.getY());
        assertEquals(f.game.getWidth(), pane.getWidth());
        assertTrue(f.game.getHeight() >= 503);
        assertTrue(f.chat.getHeight() >= IrcEmbeddedDock.MIN_HEIGHT);
        assertTrue(f.main.getLayeredPane().getBounds().height >= pane.getY() + pane.getHeight());
    }

    private static Component find(Container parent, String name) {
        for (Component component : parent.getComponents()) {
            if (name.equals(component.getName())) return component;
            if (component instanceof Container) {
                Component found = find((Container) component, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void field(Object target, String name, Object value) throws ReflectiveOperationException {
        Field f = ClientUI.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Runs RuneLite's actual frame-sizing layout against a local canvas; no login or network. */
    private static final class Fixture {
        final LookAndFeel previous = UIManager.getLookAndFeel();
        final ContainableFrame main;
        final JPanel game = new JPanel();
        final JPanel client = new JPanel(new BorderLayout());
        final JPanel sidebar = new JPanel();
        final JPanel dockHost = new JPanel(new BorderLayout());
        final JPanel chat = new JPanel(new BorderLayout());
        final JTextField input = new JTextField("draft");
        final CountingCanvas canvas = new CountingCanvas();
        final AtomicReference<String> saved = new AtomicReference<>();
        final IrcPanelWindow host;
        final LayoutManager rootLayout;
        final LayoutManager gameLayout;
        final int focusListeners;

        Fixture() {
            RuneLiteLAF.setup();
            main = new ContainableFrame();
            try {
                Constructor<?> ctor = ClientUI.class.getDeclaredConstructors()[0];
                ctor.setAccessible(true);
                Object ui = ctor.newInstance(new RuneLiteConfig() { }, null, null, null, null, null, false, "Dock test");
                field(ui, "frame", main);
                field(ui, "content", game);
                Constructor<?> layout = Class.forName("net.runelite.client.ui.ClientUI$Layout").getDeclaredConstructor(ClientUI.class);
                layout.setAccessible(true);
                gameLayout = (LayoutManager) layout.newInstance(ui);
                game.setLayout(gameLayout);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            client.setMinimumSize(new Dimension(765, 503));
            client.setPreferredSize(new Dimension(765, 503));
            client.add(canvas);
            sidebar.setMinimumSize(new Dimension(30, 503));
            sidebar.setPreferredSize(new Dimension(30, 503));
            game.add(client);
            game.add(sidebar);
            main.setContentPane(game);
            main.setBounds(50, 50, 830, 560);
            main.setVisible(true);
            rootLayout = main.getRootPane().getLayout();
            focusListeners = main.getWindowFocusListeners().length;
            chat.add(input, BorderLayout.SOUTH);
            dockHost.add(chat);
            host = new IrcPanelWindow(dockHost, chat, () -> { }, () -> { }, () -> { }, saved::get, saved::set, () -> main);
            checkCanvas();
        }

        void checkCanvas() {
            assertSame(client, canvas.getParent());
            assertTrue(canvas.isDisplayable());
            assertEquals("native canvas peer created only once", 1, canvas.added);
            assertEquals("canvas never removed while switching IRC hosts", 0, canvas.removed);
        }

        void close() {
            host.shutdown();
            main.dispose();
            try { UIManager.setLookAndFeel(previous); }
            catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
        }
    }

    private static final class CountingCanvas extends Canvas {
        int added;
        int removed;
        @Override public void addNotify() { super.addNotify(); added++; }
        @Override public void removeNotify() { removed++; super.removeNotify(); }
    }
}
