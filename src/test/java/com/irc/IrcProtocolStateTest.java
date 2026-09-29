package com.irc;

import org.junit.After;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class IrcProtocolStateTest {
    private final SimpleIrcClient client = new SimpleIrcClient().credentials("Alice", "test", "Test");
    private final List<SimpleIrcClient.IrcEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

    public IrcProtocolStateTest() { client.addEventListener(events::add); }
    @After public void close() { client.disconnect(); }
    private void welcome() { client.processLine(":server 001 Alice :Welcome"); }

    @Test public void rejectedNickNeverChangesConfirmedIdentity() {
        welcome();
        client.setNick("Taken");
        client.processLine(":server 433 Alice Taken :Nickname in use");
        client.processLine(":server 432 Alice bad/nick :Erroneous nickname");
        assertEquals("Alice", client.getNick());
        assertTrue(client.isRegistered());
        client.processLine(":alice!u@h NICK :Bob");
        assertEquals("Bob", client.getNick());
        assertEquals("Bob", client.getConfirmedNick());
        assertTrue(events.stream().anyMatch(e -> e.getMessage() != null && e.getMessage().contains("Taken")));
    }

    @Test public void initialInvalidNickEndsRegistrationWithActionableError() {
        client.processLine(":server 432 * bad/nick :Erroneous nickname");
        assertFalse(client.isRegistered());
        assertNull(client.getConfirmedNick());
        assertTrue(events.stream().anyMatch(e -> e.getType() == SimpleIrcClient.IrcEvent.Type.DISCONNECT
                && e.getMessage().contains("choose another nickname")));
    }

    @Test public void collisionRetriesAreBounded() {
        for (int i = 0; i < 6; i++) client.processLine(":server 433 * Alice :Nickname in use");
        assertEquals(1, events.stream().filter(e -> e.getType() == SimpleIrcClient.IrcEvent.Type.DISCONNECT).count());
        assertNull(client.getConfirmedNick());
    }

    @Test public void desiredChannelsAndConfirmedMembershipAreIndependent() {
        client.joinChannel("#One", "old");
        client.joinChannel("#one", "corrected");
        client.joinChannel("#cancel", "");
        client.leaveChannel("#CANCEL");
        assertEquals(Collections.singletonMap("#one", "corrected"), client.getDesiredChannels());
        welcome();
        client.processLine(":server 475 Alice #one :Bad key");
        assertTrue(client.getChannels().isEmpty());
        client.processLine(":alice!u@h JOIN :#One");
        assertEquals(Collections.singleton("#One"), client.getChannels());
        client.leaveChannel("#one");
        assertFalse(client.getChannels().isEmpty()); // PART has not been confirmed yet.
        client.processLine(":ALICE!u@h PART #ONE :bye");
        assertTrue(client.getChannels().isEmpty());
        assertTrue(client.getDesiredChannels().isEmpty());
        client.joinChannel("#one", "corrected");
        client.processLine(":Alice!u@h JOIN #one");
        client.processLine(":op!u@h KICK #one alice :bye");
        assertTrue(client.getChannels().isEmpty());
        assertTrue(client.getChannelUsers("#one").isEmpty());
        // Explicit reload may retry; there is no automatic rejoin on kick.
        assertEquals("corrected", client.getDesiredChannels().get("#one"));
    }

    @Test public void namesChunksPreserveInterleavedDeparturesJoinsRenamesAndModes() {
        welcome();
        client.processLine(":Alice!u@h JOIN #room");
        client.processLine(":server 353 Alice = #room :Alice bob @dave erin frank");
        client.processLine(":bob!u@h PART #room :bye");
        client.processLine(":carol!u@h JOIN #room");
        client.processLine(":dave!u@h NICK :David");
        client.processLine(":erin!u@h QUIT :bye");
        client.processLine(":op!u@h MODE #room +v-o carol David");
        client.processLine(":op!u@h KICK #room frank :bye");
        // A later chunk contains stale users and modes; replay live changes at 366.
        client.processLine(":server 353 Alice = #room :bob @dave erin frank");
        client.processLine(":server 366 Alice #room :End");
        List<ChannelUserList.Entry> users = client.getChannelUsers("#ROOM");
        assertEquals(Arrays.asList("carol", "Alice", "David"), users.stream().map(ChannelUserList.Entry::getNick).collect(Collectors.toList()));
        assertEquals("+", users.get(0).getPrefix());
        assertEquals("", users.get(2).getPrefix());
    }

    @Test public void caseMappingUsesServerRulesAndReindexesExistingUsers() {
        welcome();
        client.processLine(":Alice!u@h JOIN #room");
        client.processLine(":[bob]!u@h JOIN #room");
        assertTrue(client.sameName("[bob]", "{BOB}"));
        client.processLine(":server 005 Alice CASEMAPPING=ascii :supported");
        assertFalse(client.sameName("[bob]", "{BOB}"));
        client.processLine(":[BOB]!u@h PART #room");
        assertEquals(1, client.getChannelUsers("#room").size());
    }

    @Test public void malformedRepliesDoNotBreakParsingAndErrorsIncludeTarget() {
        welcome();
        client.processLine(":server 475 Alice #room");
        client.processLine(":server 311 Alice bob user host :missing realname");
        client.processLine(":bob!u@h PRIVMSG Alice :\u0001");
        client.processLine(":server 403 Alice #missing :No such channel");
        assertTrue(events.stream().anyMatch(e -> e.getMessage() != null && e.getMessage().contains("403: #missing")));
        assertTrue(client.isRegistered());
    }

    @Test public void pendingNamesDeltasSurviveCaseMappingChange() {
        welcome();
        client.processLine(":Alice!u@h JOIN #[room]");
        client.processLine(":server 353 Alice = #[room] :Alice bob");
        client.processLine(":bob!u@h PART #[room]");
        client.processLine(":server 005 Alice CASEMAPPING=ascii :supported");
        client.processLine(":server 353 Alice = #[room] :bob");
        client.processLine(":server 366 Alice #[room] :End");
        assertEquals(Collections.singletonList("Alice"), client.getChannelUsers("#[room]").stream()
                .map(ChannelUserList.Entry::getNick).collect(Collectors.toList()));
    }

    @Test public void fatalRegistrationErrorClosesWithServerReason() {
        client.processLine(":server 465 * :You are banned from this server");
        assertTrue(events.stream().anyMatch(e -> e.getType() == SimpleIrcClient.IrcEvent.Type.DISCONNECT
                && e.getMessage().contains("You are banned")));
    }

    @Test public void unsentMessagesCannotProduceSuccessCallbacks() {
        AtomicInteger echoes = new AtomicInteger();
        assertFalse(client.sendMessage("#room", "offline", echoes::incrementAndGet));
        welcome();
        assertFalse(client.sendMessage("#room", "hello\r\nQUIT", echoes::incrementAndGet));
        assertFalse(client.sendMessage("#room", "é".repeat(260), echoes::incrementAndGet));
        assertEquals(0, echoes.get());
    }
}
