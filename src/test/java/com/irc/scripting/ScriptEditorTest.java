package com.irc.scripting;

import org.junit.Test;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Isolated Swing component tests, without launching RuneLite or interacting with the game. */
public class ScriptEditorTest {
    @Test public void focusedCaretPaintsAndBlinksAfterTypingHighlightingAndSaving() throws Exception {
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        javax.swing.LookAndFeel previous = javax.swing.UIManager.getLookAndFeel();
        AtomicReference<javax.swing.JFrame> frame = new AtomicReference<>();
        AtomicReference<ScriptEditor> editorRef = new AtomicReference<>();
        AtomicReference<javax.swing.Timer> timerRef = new AtomicReference<>();
        java.util.concurrent.CompletableFuture<Void> done = new java.util.concurrent.CompletableFuture<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                net.runelite.client.ui.laf.RuneLiteLAF.setup();
                ScriptEditor editor = new ScriptEditor(() -> {}, () -> {}); editorRef.set(editor);
                JTextPane text = pane(editor);
                javax.swing.JFrame window = new javax.swing.JFrame("Editor caret test"); frame.set(window);
                window.add(editor); window.setSize(650, 350); window.setVisible(true);
                window.toFront(); text.requestFocusInWindow();
                boolean[] started = {false}, visible = {false};
                int[] transitions = {0};
                javax.swing.Timer poll = new javax.swing.Timer(40, e -> {
                    try {
                        if (!started[0]) {
                            if (!text.isFocusOwner()) { text.requestFocusInWindow(); return; }
                            editor.source("alias hello { echo hi }");
                            text.setCaretPosition(text.getDocument().getLength());
                            text.replaceSelection("\n");
                            editor.editable(false);
                            assertFalse("busy editor hides the insertion caret", text.getCaret().isVisible());
                            editor.editable(true);
                            assertTrue("saving restores the focused caret", text.getCaret().isVisible());
                            visible[0] = true; started[0] = true;
                        } else if (text.getCaret().isVisible() != visible[0]) {
                            visible[0] = !visible[0];
                            if (++transitions[0] >= 2) {
                                assertTrue("editor retained focus through highlighting", text.isFocusOwner());
                                assertEquals("alias hello { echo hi }\n", editor.source());
                                text.getCaret().setVisible(true);
                                java.awt.image.BufferedImage withCaret = paint(text);
                                text.getCaret().setVisible(false);
                                java.awt.image.BufferedImage withoutCaret = paint(text);
                                int differences = 0;
                                for (int y = 0; y < withCaret.getHeight(); y++)
                                    for (int x = 0; x < withCaret.getWidth(); x++)
                                        if (withCaret.getRGB(x, y) != withoutCaret.getRGB(x, y)) differences++;
                                assertTrue("the blinking caret actually paints pixels", differences > 0);
                                ((javax.swing.Timer) e.getSource()).stop(); done.complete(null);
                            }
                        }
                    } catch (Throwable failure) {
                        ((javax.swing.Timer) e.getSource()).stop(); done.completeExceptionally(failure);
                    }
                });
                timerRef.set(poll); poll.start();
            });
            done.get(6, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (timerRef.get() != null) timerRef.get().stop();
                if (editorRef.get() != null) editorRef.get().stop();
                if (frame.get() != null) frame.get().dispose();
                try { javax.swing.UIManager.setLookAndFeel(previous); }
                catch (javax.swing.UnsupportedLookAndFeelException ex) { throw new AssertionError(ex); }
            });
        }
    }

    private static java.awt.image.BufferedImage paint(JTextPane text) {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(text.getWidth(), text.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        try { text.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }

    @Test public void caretIsVisibleAndBlinksUnderRuneLiteTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.LookAndFeel previous = javax.swing.UIManager.getLookAndFeel();
            net.runelite.client.ui.laf.RuneLiteLAF.setup();
            ScriptEditor editor = new ScriptEditor(() -> {}, () -> {});
            try {
                JTextPane text = pane(editor);
                assertTrue("the insertion caret should blink", text.getCaret().getBlinkRate() > 0);
                assertNotEquals("caret must contrast with the editor", text.getBackground(), text.getCaretColor());
                assertEquals("caret remains visible at small editor font sizes", 2, text.getClientProperty("caretWidth"));
            } finally {
                editor.stop();
                try { javax.swing.UIManager.setLookAndFeel(previous); }
                catch (javax.swing.UnsupportedLookAndFeelException ex) { throw new AssertionError(ex); }
            }
        });
    }
    private static JTextPane pane(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JTextPane) return (JTextPane) child;
            if (child instanceof Container) { JTextPane found = pane((Container) child); if (found != null) return found; }
        }
        return null;
    }
    @Test public void normalizesLineEndingsAndIndentsWithoutLosingUndo() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ScriptEditor editor = new ScriptEditor(() -> {}, () -> {});
            try {
                editor.source("alias hello {\r\n  echo hi\r\n}");
                assertEquals("alias hello {\n  echo hi\n}", editor.source());
                JTextPane text = pane(editor); assertNotNull(text);
                text.setCaretPosition(13);
                text.getActionMap().get("newline").actionPerformed(new ActionEvent(text, 0, ""));
                assertTrue(editor.source(), editor.source().startsWith("alias hello {\n  \n"));
                text.getActionMap().get("undo").actionPerformed(new ActionEvent(text, 0, ""));
                assertEquals("alias hello {\n  echo hi\n}", editor.source());
                editor.source("\nhello"); text.setCaretPosition(0);
                text.getActionMap().get("newline").actionPerformed(new ActionEvent(text, 0, ""));
                assertEquals("\n\nhello", editor.source());
            } finally { editor.stop(); }
        });
    }
    @Test public void oversizedPasteIsRejectedAndDirtyCallbacksIgnoreLoading() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ScriptEditor editor = new ScriptEditor(() -> {}, () -> {});
            AtomicReference<Boolean> changed = new AtomicReference<>(false);
            editor.onChange(() -> changed.set(true));
            try {
                editor.source("hello"); assertFalse(changed.get());
                JTextPane text = pane(editor); text.setCaretPosition(5);
                text.replaceSelection("x".repeat(IrcScript.MAX_SOURCE));
                assertEquals("hello", editor.source()); assertFalse(changed.get());
                text.replaceSelection(" world"); assertTrue(changed.get()); assertEquals("hello world", editor.source());
            } finally { editor.stop(); }
        });
    }
}
