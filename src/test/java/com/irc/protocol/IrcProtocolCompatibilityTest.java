package com.irc.protocol;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import static org.junit.Assert.*;

/** Characterization fixtures captured from dff9c44 before the cleanup. Synthetic credentials only. */
public class IrcProtocolCompatibilityTest {
    private static class RecordingClient extends SimpleIrcClient {
        final List<String> transcript = new ArrayList<>();
        @Override public synchronized void sendRawLine(String line) { transcript.add("URGENT " + escape(line)); }
        @Override public synchronized boolean sendCommand(String line, Runnable sent) {
            transcript.add("COMMAND " + escape(line));
            return true;
        }
    }

    private static String escape(String value) {
        return value == null ? "<null>" : value.replace("\\", "\\\\").replace("\u0001", "\\x01")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void event(List<String> transcript, String prefix, SimpleIrcClient.IrcEvent e) {
        transcript.add(prefix + e.getType() + " | " + escape(e.getSource()) + " | " + escape(e.getTarget())
                + " | " + escape(e.getMessage()) + " | " + escape(e.getAdditionalData()));
        if (e.getHistoryMessages() != null) e.getHistoryMessages().forEach(h -> event(transcript, "  HISTORY ", h));
    }

    private void replay(String name, String password, String... lines) throws Exception {
        RecordingClient client = new RecordingClient();
        client.credentials("Alice", "test", "Test");
        client.sasl("Alice", password);
        SimpleIrcClient.IrcEventListener listener = e -> event(client.transcript, "EVENT ", e);
        client.addEventListener(listener);
        try {
            for (String line : lines) {
                client.transcript.add("INPUT " + escape(line));
                client.processLine(line);
                client.transcript.add("STATE " + client.getNick() + " | " + client.getConfirmedNick()
                        + " | " + client.isRegistered() + " | " + new TreeSet<>(client.getChannels()));
                for (String channel : new TreeSet<>(client.getChannels())) {
                    List<String> users = new ArrayList<>();
                    client.getChannelUsers(channel).forEach(u -> users.add(u.getPrefix() + u.getNick()));
                    client.transcript.add("ROSTER " + channel + " " + users);
                }
                for (ChannelListEntry row : client.getChannelListSnapshot()) {
                    client.transcript.add("LIST " + row.getName() + " | " + row.getUserCount() + " | " + row.getTopic());
                }
            }
            Path fixture = Paths.get("src/test/resources/irc/" + name + ".txt");
            assertEquals(name + " differs from dff9c44", Files.readAllLines(fixture, StandardCharsets.UTF_8), client.transcript);
        } finally {
            client.removeEventListener(listener);
            client.disconnect();
        }
    }

    @Test public void capabilityNegotiationAndSaslChunkBoundary() throws Exception {
        // Two NULs + five-character account + 293 password bytes => exactly 400 base64 bytes.
        replay("cap-sasl", "p".repeat(293),
                ":server CAP * LS * :sasl=PLAIN batch",
                ":server CAP * LS :server-time chathistory",
                ":server CAP * ACK :sasl batch server-time chathistory",
                "AUTHENTICATE +", ":server 903 Alice :SASL authentication successful",
                ":server 001 Alice :Welcome", "PING :keep alive", ":Alice!u@h JOIN #room");
    }

    @Test public void rejectedCapabilitiesAndSaslFailure() throws Exception {
        replay("cap-rejected", "test-password", ":server CAP * LS :sasl",
                ":server CAP * ACK :sasl", "AUTHENTICATE +",
                ":server 904 Alice :SASL authentication failed", ":server CAP * NAK :chathistory",
                ":server 001 Alice :Welcome", ":Alice!u@h JOIN #room");
    }

    @Test public void messagesCtcpAndHistoryTags() throws Exception {
        replay("messages-history", "", ":server 001 Alice :Welcome",
                ":bob!u@h PRIVMSG #room :hello : world", ":bob!u@h PRIVMSG Alice :private",
                ":bob!u@h PRIVMSG #room :\u0001ACTION waves\u0001",
                ":bob!u@h PRIVMSG Alice :\u0001VERSION\u0001",
                ":bob!u@h PRIVMSG Alice :\u0001PING 1234\u0001",
                ":bob!u@h NOTICE Alice :notice", ":server NOTICE Alice :server notice",
                ":server BATCH +h chathistory #room",
                "@batch=h;time=2026-01-01T00:00:00Z :bob!u@h PRIVMSG #room :old message",
                "@batch=h;time=2026-01-01T00:00:01Z :bob!u@h PRIVMSG #room :\u0001ACTION waved\u0001",
                "@batch=h :bob!u@h NOTICE #room :ignored within history",
                ":server BATCH -h", ":bob!u@h PRIVMSG #room :live after batch");
    }

    @Test public void rosterTransactionsNickChangesAndMembership() throws Exception {
        replay("rosters", "", ":server 001 Alice :Welcome",
                ":server 005 Alice PREFIX=(ov)@+ CHANMODES=b,k,l,imnpst CASEMAPPING=rfc1459 :supported",
                ":Alice!u@h JOIN #room", ":server 353 Alice = #room :Alice bob @dave erin frank",
                ":bob!u@h PART #room :bye", ":carol!u@h JOIN #room", ":dave!u@h NICK :David",
                ":erin!u@h QUIT :bye", ":op!u@h MODE #room +v-o carol David",
                ":op!u@h KICK #room frank :bye", ":server 353 Alice = #room :bob @dave erin frank",
                ":server 366 Alice #room :End", ":Alice!u@h NICK :Alicia",
                ":server 433 Alicia Taken :Nickname in use", ":server 432 Alicia bad/nick :Erroneous nickname",
                ":server 005 Alicia CASEMAPPING=ascii :supported", ":op!u@h KICK #room Alicia :bye");
    }

    @Test public void TopicsWhoisListAndChannelErrors() throws Exception {
        replay("numerics", "", ":server 001 Alice :Welcome",
                ":server 332 Alice #room :topic", ":server 333 Alice #room bob 123456",
                ":bob!u@h TOPIC #room :changed topic", ":server 324 Alice #room +nt",
                ":server 301 Alice bob :away", ":server 311 Alice bob user host * :Real Name",
                ":server 312 Alice bob irc.example :server info", ":server 313 Alice bob :operator",
                ":server 317 Alice bob 123 456 :idle", ":server 319 Alice bob :@#room +#other",
                ":server 318 Alice bob :End of WHOIS", ":server 321 Alice Channel :Users Name",
                ":server 322 Alice #room 42 :topic", ":server 323 Alice :End of LIST",
                ":server 263 Alice LIST :Try again later", ":server 475 Alice #locked :Bad key",
                ":server 403 Alice #missing :No such channel", ":server 471 Alice #full :Channel is full",
                ":server 473 Alice #invite :Invite only", ":server 474 Alice #banned :Banned",
                ":server 475 Alice #malformed", ":server 311 Alice bob :malformed",
                ":bob!u@h PRIVMSG Alice :\u0001");
    }
}
