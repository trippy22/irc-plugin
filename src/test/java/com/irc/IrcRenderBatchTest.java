package com.irc;

import org.junit.Test;
import javax.swing.*;
import java.awt.Font;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class IrcRenderBatchTest {
    private static class CountingPane extends IrcPanel.ChannelPane {
        int renders;
        CountingPane(IrcConfig config) { super(new Font("Dialog", Font.PLAIN, 12), config, null); renders = 0; }
        @Override public void setText(String text) { super.setText(text); renders++; }
    }

    @Test public void messageBurstRendersOnceAndKeepsBoundedHistory() throws Exception {
        AtomicReference<CountingPane> ref = new AtomicReference<>();
        IrcConfig config = new IrcConfig() {
            @Override public String username() { return "Alice"; }
            @Override public String password() { return ""; }
            @Override public int getMaxScrollback() { return 20; }
        };
        SwingUtilities.invokeAndWait(() -> {
            CountingPane pane = new CountingPane(config);
            ref.set(pane);
            for (int i = 0; i < 100; i++) pane.appendMessage(new IrcMessage("#room", "Alice", "line-" + i + "-end",
                    IrcMessage.MessageType.CHAT, Instant.now()), config);
            assertEquals(0, pane.renders);
        });
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(1, ref.get().renders);
            assertFalse(ref.get().getText().contains("line-79-end"));
            assertTrue(ref.get().getText().contains("line-80-end"));
            assertTrue(ref.get().getText().contains("line-99-end"));
        });
    }
}
