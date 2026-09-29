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
        AtomicReference<IrcChatModel> modelRef = new AtomicReference<>();
        IrcConfig config = new IrcConfig() {
            @Override public String username() { return "Alice"; }
            @Override public String password() { return ""; }
            @Override public int getMaxScrollback() { return 20; }
        };
        SwingUtilities.invokeAndWait(() -> {
            CountingPane pane = new CountingPane(config);
            ref.set(pane);
            IrcChatModel model = new IrcChatModel();
            modelRef.set(model);
            model.setHistoryLimit(config.getMaxScrollback());
            model.open("#room", true);
            model.listen(state -> pane.showMessages(state.conversations.get(1).messages));
            for (int i = 0; i < 100; i++) model.append(new IrcMessage("#room", "Alice", "line-" + i + "-end",
                    IrcMessage.MessageType.CHAT, Instant.now()), false);
            assertEquals(0, pane.renders);
        });
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(1, ref.get().renders);
            assertFalse(ref.get().getText().contains("line-79-end"));
            assertTrue(ref.get().getText().contains("line-80-end"));
            assertTrue(ref.get().getText().contains("line-99-end"));
            modelRef.get().clear("#room");
            modelRef.get().append(new IrcMessage("#room", "Alice", "after-clear",
                    IrcMessage.MessageType.CHAT, Instant.now()), false);
        });
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(2, ref.get().renders);
            assertFalse(ref.get().getText().contains("line-99-end"));
            assertTrue(ref.get().getText().contains("after-clear"));
        });
    }
}
