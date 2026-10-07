package com.irc;

import org.junit.Before;
import org.junit.Test;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import net.runelite.client.ui.laf.RuneLiteLAF;
import static org.junit.Assert.*;

/** Real windows exercise asynchronous native moves, resize, hiding, restoration and cleanup. */
public class IrcBottomDockTest {
    @Before
    public void requiresDesktop() {
        org.junit.Assume.assumeFalse(GraphicsEnvironment.isHeadless());
    }

    @Test
    public void followsOwnerResizesReleasesAndRestoresAttachment() throws Exception {
        AtomicReference<Fixture> ref = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = new Fixture();
                ref.set(f);
                f.host.setDetached(true, false);
                f.popout().setBounds(140, 120, 900, 520);
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = ref.get();
                f.host.attachBelow();
                assertTrue(f.host.isBottomDocked());
                assertEquals(f.main.getWidth(), f.popout().getWidth());
                assertEquals(f.main.getY() + f.main.getHeight(), f.popout().getY());
                f.main.setBounds(100, 70, 850, 420);
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = ref.get();
                assertTrue(f.host.isBottomDocked());
                assertEquals(100, f.popout().getX());
                assertEquals(490, f.popout().getY());
                assertEquals(850, f.popout().getWidth());
                f.popout().setSize(850, 310);
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = ref.get();
                assertTrue(f.host.isBottomDocked());
                f.main.setVisible(false);
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = ref.get();
                assertFalse(f.popout().isVisible());
                f.main.setVisible(true);
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = ref.get();
                assertTrue(f.popout().isVisible());
                f.main.setExtendedState(Frame.ICONIFIED);
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = ref.get();
                assertFalse("minimizing RuneLite hides attached chat", f.popout().isVisible());
                f.host.toFront();
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = ref.get();
                assertTrue("popout command restores both windows", f.popout().isVisible());
                assertEquals(0, f.main.getExtendedState() & Frame.ICONIFIED);
                f.host.setDetached(false, false);
                assertEquals(310, PopOutGeometry.parse(f.saved.get()).dockHeight);
                f.host.setDetached(true, false);
                assertTrue(f.host.isBottomDocked());
                assertEquals(310, f.popout().getHeight());
                assertEquals("draft survives", f.draft.getText());
                f.host.toggleBottomDock();
                assertFalse(f.host.isBottomDocked());
                assertEquals(new Rectangle(140, 120, 900, 520), f.popout().getBounds());
                f.popout().setLocation(100, 498);
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = ref.get();
                assertTrue("dragging near the edge snaps", f.host.isBottomDocked());
                f.host.toggleBottomDock();
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = ref.get();
                assertFalse("Float must not immediately snap back", f.host.isBottomDocked());
                f.host.attachBelow();
                f.popout().setLocation(250, 180);
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                Fixture f = ref.get();
                assertFalse("dragging away releases", f.host.isBottomDocked());
                assertEquals(new Point(250, 180), f.popout().getLocation());
                JFrame closed = f.popout();
                f.host.shutdown();
                assertFalse(closed.isDisplayable());
                assertEquals(f.originalComponents, f.main.getComponentListeners().length);
                assertEquals(f.originalWindows, f.main.getWindowListeners().length);
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (ref.get() != null) ref.get().close(); });
        }
    }

    @Test
    public void compactPanelKeepsControlsAndComposerVisible() throws Exception {
        AtomicReference<IrcPanel> panelRef = new AtomicReference<>();
        AtomicReference<JFrame> mainRef = new AtomicReference<>();
        LookAndFeel previous = UIManager.getLookAndFeel();
        try {
            SwingUtilities.invokeAndWait(() -> {
                RuneLiteLAF.setup();
                IrcPanel panel = new IrcPanel();
                panelRef.set(panel);
                try {
                    Field config = IrcPanel.class.getDeclaredField("config");
                    config.setAccessible(true);
                    config.set(panel, new IrcConfig() {
                        @Override public String username() { return "Mikey"; }
                        @Override public String password() { return ""; }
                    });
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                panel.init((buffer, text) -> { }, (network, channel, password) -> { },
                        buffer -> { }, network -> { }, (network, query) -> { }, () -> { });
                panel.initializeGui();
                JFrame main = new JFrame("RuneLite layout test");
                mainRef.set(main);
                main.add(panel);
                main.setBounds(60, 60, 800, 400);
                main.setVisible(true);
            });
            SwingUtilities.invokeAndWait(() -> {
                IrcPanel panel = panelRef.get();
                panel.addChannel("#runelite");
                panel.setFocusedChannel("#runelite");
                panel.setNetworkConnected(NetworkConfig.SWIFTIRC_ID, true);
                panel.setChannelUsers("#runelite", Collections.singletonList(new ChannelUserList.Entry("Mikey", "+", 1)));
                panel.addMessage(new IrcMessage("#runelite", "Mikey", "IRC can now follow RuneLite below the game.",
                        IrcMessage.MessageType.CHAT, java.time.Instant.now()));
                panel.inputField.setText("Unfinished message");
                panel.setDetached(true, false);
                JButton attach = (JButton) find(panel.getChatContent(), "ircAttachBelow");
                assertTrue(attach.isEnabled());
                attach.doClick();
            });
            settle();
            SwingUtilities.invokeAndWait(() -> {
                IrcPanel panel = panelRef.get();
                JFrame popout = (JFrame) SwingUtilities.getWindowAncestor(panel.getChatContent());
                assertEquals(280, popout.getHeight());
                assertEquals("Float", ((JButton) find(panel.getChatContent(), "ircAttachBelow")).getText());
                assertFalse(find(panel.getChatContent(), "ircHints").isVisible());
                for (int width : new int[] {800, 640}) {
                    mainRef.get().setSize(width, 400);
                    mainRef.get().dispatchEvent(new java.awt.event.ComponentEvent(mainRef.get(), java.awt.event.ComponentEvent.COMPONENT_RESIZED));
                    popout.validate();
                    assertControlsVisible((Container) find(panel.getChatContent(), "ircToolbar"));
                    Rectangle input = SwingUtilities.convertRectangle(panel.inputField.getParent(), panel.inputField.getBounds(), panel.getChatContent());
                    assertTrue(panel.getChatContent().getVisibleRect().contains(input));
                    assertTrue(panel.inputField.getWidth() > 200);
                }
                BufferedImage preview = new BufferedImage(panel.getChatContent().getWidth(), panel.getChatContent().getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = preview.createGraphics();
                panel.getChatContent().printAll(graphics);
                graphics.dispose();
                try {
                    File output = new File("build/previews/irc-bottom-dock.png");
                    output.getParentFile().mkdirs();
                    ImageIO.write(preview, "png", output);
                } catch (java.io.IOException e) { throw new AssertionError(e); }
            });
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (panelRef.get() != null) panelRef.get().shutdown();
                if (mainRef.get() != null) mainRef.get().dispose();
                try { UIManager.setLookAndFeel(previous); }
                catch (UnsupportedLookAndFeelException e) { throw new AssertionError(e); }
            });
        }
    }

    private static Component find(Container parent, String name) {
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container) {
                Component found = find((Container) child, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void assertControlsVisible(Container parent) {
        for (Component child : parent.getComponents()) {
            if (!child.isShowing()) continue;
            if (child instanceof AbstractButton || child instanceof JComboBox) {
                assertTrue("control is clipped: " + child, parent.contains(child.getX(), child.getY()));
                assertTrue("control is clipped: " + child, parent.contains(child.getX() + child.getWidth() - 1,
                        child.getY() + child.getHeight() - 1));
            }
        }
    }

    private static void settle() throws Exception {
        Thread.sleep(450);
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static final class Fixture {
        final JFrame main = new JFrame("Dock test");
        final JPanel dock = new JPanel(new BorderLayout());
        final JPanel content = new JPanel(new BorderLayout());
        final JTextField draft = new JTextField("draft survives");
        final AtomicReference<String> saved = new AtomicReference<>();
        final IrcPanelWindow host;
        final int originalComponents;
        final int originalWindows;

        Fixture() {
            main.setBounds(80, 60, 800, 400);
            main.add(dock);
            // Deliberately keep the sidebar outside the main window, as when it is disabled.
            main.remove(dock);
            content.add(draft, BorderLayout.SOUTH);
            dock.add(content, BorderLayout.CENTER);
            main.setVisible(true);
            originalComponents = main.getComponentListeners().length;
            originalWindows = main.getWindowListeners().length;
            host = new IrcPanelWindow(dock, content, () -> { }, () -> { }, () -> { },
                    saved::get, saved::set, () -> main);
        }

        JFrame popout() { return (JFrame) SwingUtilities.getWindowAncestor(content); }

        void close() { host.shutdown(); main.dispose(); }
    }
}
