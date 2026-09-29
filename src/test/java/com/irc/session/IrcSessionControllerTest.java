package com.irc.session;

import com.irc.model.IrcChatModel;
import com.irc.IrcConfig;
import com.irc.ui.IrcPanel;

import org.junit.Test;
import javax.swing.SwingUtilities;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.irc.protocol.ProtocolTestInput.receive;
import static org.junit.Assert.*;

public class IrcSessionControllerTest {
    private static final IrcConfig CONFIG = new IrcConfig() {
        public String username() { return "Alice"; }
        public String password() { return ""; }
        public boolean autofocusOnNewTab() { return true; }
    };
    private static final class OfflineAdapter extends IrcAdapter {
        @Override public void connect() { receive(getClient(), ":server 001 " + getNick() + " :Welcome"); }
    }

    @Test public void lateJoinAndNamesCannotReopenClosedConversation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            IrcPanel panel = new IrcPanel();
            IrcSessionController controller = new IrcSessionController(OfflineAdapter::new);
            controller.initialize(CONFIG, m -> panel.getModel().append(m, false), panel, "Alice");
            try {
                controller.connect();
                controller.joinChannel("#room", "key");
                assertEquals(IrcChatModel.Membership.JOINING, panel.getModel().snapshot().conversations.get(1).membership);
                controller.leaveChannel("#room");
                receive(controller.getClient(), ":Alice!u@h JOIN #room");
                receive(controller.getClient(), ":server 353 Alice = #room :Alice bob");
                receive(controller.getClient(), ":server 366 Alice #room :End");
                assertEquals(1, panel.getModel().snapshot().conversations.size());
                controller.joinChannel("#room", "new-key");
                receive(controller.getClient(), ":Alice!u@h JOIN #room");
                assertEquals(IrcChatModel.Membership.JOINED, panel.getModel().snapshot().conversations.get(1).membership);
                receive(controller.getClient(), ":op!u@h KICK #room Alice :reason");
                assertEquals(IrcChatModel.Membership.KICKED, panel.getModel().snapshot().conversations.get(1).membership);
            } finally { controller.clearPanel(); controller.disconnect("test"); }
        });
    }

    @Test public void reloadReplacesSessionButPreservesConversationIdentityAndDraft() throws Exception {
        AtomicReference<IrcSessionController> ref = new AtomicReference<>();
        AtomicReference<IrcChatModel> model = new AtomicReference<>();
        List<IrcAdapter> sessions = new CopyOnWriteArrayList<>();
        CountDownLatch replaced = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            IrcPanel panel = new IrcPanel();
            model.set(panel.getModel());
            IrcSessionController controller = new IrcSessionController(() -> {
                IrcAdapter adapter = new OfflineAdapter(); sessions.add(adapter); return adapter;
            });
            ref.set(controller);
            controller.initialize(CONFIG, m -> panel.getModel().append(m, false), panel, "Alice");
            controller.connect();
            controller.joinChannel("#room", "key");
            receive(controller.getClient(), ":Alice!u@h NICK :Bob");
            model.get().draft("#room", "keep me");
            controller.joinChannel("#other", "");
            model.get().select("#room");
            model.get().listen(state -> { if (sessions.size() == 2 && state.connection == IrcChatModel.Connection.READY) replaced.countDown(); });
            controller.reload();
            controller.reload(); // One replacement even if Reload is clicked twice.
        });
        try {
            assertTrue(replaced.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(2, sessions.size());
                assertEquals("Bob", ref.get().getNick());
                assertEquals("key", ref.get().getClient().getDesiredChannels().get("#room"));
                IrcChatModel.Conversation room = model.get().snapshot().conversations.stream()
                        .filter(b -> b.name.equals("#room")).findFirst().get();
                assertEquals("keep me", room.draft);
                assertEquals("#room", model.get().snapshot().selected);
                assertEquals(2, room.id);
                assertNotSame(sessions.get(0).getClient(), ref.get().getClient());
            });
        } finally { SwingUtilities.invokeAndWait(() -> { ref.get().clearPanel(); ref.get().disconnect("test"); }); }
    }
}
