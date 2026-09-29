package com.irc.model;

import com.irc.protocol.ChannelUserList;

import org.junit.Test;
import javax.swing.SwingUtilities;
import java.time.Instant;
import java.util.Collections;
import static org.junit.Assert.*;

public class IrcChatModelTest {
    private static IrcMessage message(String channel, String text) {
        return new IrcMessage(channel, "Alice", text, IrcMessage.MessageType.CHAT, Instant.now());
    }

    @Test public void draftsSelectionAndUnreadBelongToConversations() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            IrcChatModel model = new IrcChatModel();
            model.open("#one", true);
            model.draft("#one", "first draft");
            model.open("#two", true);
            model.draft("#two", "second draft");
            model.append(message("#ONE", "hello"), false);
            assertTrue(model.snapshot().conversations.get(1).unread);
            model.select("#one");
            assertFalse(model.snapshot().conversations.get(1).unread);
            assertEquals("first draft", model.snapshot().conversations.get(1).draft);
            assertEquals("second draft", model.snapshot().conversations.get(2).draft);
        });
    }

    @Test public void closedChannelsIgnoreLateMessagesRostersAndStateUntilExplicitReopen() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            IrcChatModel model = new IrcChatModel();
            model.open("#[room]", true);
            model.close("#{ROOM}");
            model.append(message("#[room]", "late NAMES"), true);
            model.users("#[room]", Collections.singletonList(new ChannelUserList.Entry("Alice", "", 0)));
            model.membership("#[room]", IrcChatModel.Membership.JOINED, "late JOIN");
            assertEquals(1, model.snapshot().conversations.size());
            assertEquals("System", model.snapshot().selected);
            model.open("#[room]", true);
            model.append(message("#{room}", "new message"), false);
            assertEquals(1, model.snapshot().conversations.get(1).messages.size());
        });
    }

    @Test public void renameMergesExistingPrivateConversationWithoutLosingDraftsOrHistory() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            IrcChatModel model = new IrcChatModel();
            model.open("Alice", true); model.draft("Alice", "one");
            model.append(message("Alice", "old nick"), false);
            model.open("Bob", false); model.draft("Bob", "two");
            model.append(message("Bob", "new nick"), false);
            model.rename("alice", "BOB");
            assertEquals(2, model.snapshot().conversations.size());
            IrcChatModel.Conversation conversation = model.snapshot().conversations.get(1);
            assertEquals(2, conversation.messages.size());
            assertEquals("two one", conversation.draft);
            assertEquals(conversation.name, model.snapshot().selected);
        });
    }

    @Test public void snapshotsRemainImmutableAndHistoryIsBounded() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            IrcChatModel model = new IrcChatModel();
            model.setHistoryLimit(2);
            model.append(message("#room", "one"), false);
            IrcChatModel.Snapshot old = model.snapshot();
            model.append(message("#room", "two"), false);
            model.append(message("#room", "three"), false);
            assertEquals(1, old.conversations.get(1).messages.size());
            assertEquals("two", model.snapshot().conversations.get(1).messages.get(0).getContent());
            try { old.conversations.clear(); fail("mutable snapshot"); } catch (UnsupportedOperationException expected) { }
            model.connection(IrcChatModel.Connection.OFFLINE, null);
            assertEquals(2, model.snapshot().conversations.get(1).messages.size());
        });
    }

    @Test(expected = IllegalStateException.class) public void mutationsHaveOneThreadOwner() {
        new IrcChatModel().open("#room", true);
    }
}
