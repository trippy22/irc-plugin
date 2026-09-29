package com.irc;

import org.junit.Test;
import javax.swing.SwingUtilities;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real loopback sockets, with no dependency on a public IRC server. */
public class IrcConnectionTest {
    private static SimpleIrcClient client(ServerSocket server, boolean tls) {
        return new SimpleIrcClient().server("127.0.0.1", server.getLocalPort(), tls)
                .credentials("Alice", "test", "Test");
    }

    private static BufferedReader reader(Socket peer) throws IOException {
        peer.setSoTimeout(3000);
        return new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
    }

    private static void send(Socket peer, String line) throws IOException {
        peer.getOutputStream().write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        peer.getOutputStream().flush();
    }

    private static void handshake(BufferedReader in, String nick) throws IOException {
        assertEquals("CAP LS 302", in.readLine());
        assertEquals("NICK " + nick, in.readLine());
        assertTrue(in.readLine().startsWith("USER "));
    }

    private static void closed(SimpleIrcClient client) throws Exception {
        CountDownLatch close = new CountDownLatch(1);
        client.onDisconnected(close::countDown);
        client.disconnect("Test complete");
        assertTrue("disconnect completed", close.await(2, TimeUnit.SECONDS));
        Field field = SimpleIrcClient.class.getDeclaredField("executor");
        field.setAccessible(true);
        ExecutorService workers = (ExecutorService) field.get(client);
        assertTrue("session workers terminated", workers.awaitTermination(2, TimeUnit.SECONDS));
        assertFalse(client.isConnected());
        assertFalse(client.isRegistered());
    }

    @Test public void registrationGatesJoinsAndReloadRetainsNickChannelsAndKeys() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            SimpleIrcClient first = client(server, false);
            try {
                first.joinChannel("#keep", "correct-key");
                first.joinChannel("#cancel", "");
                first.leaveChannel("#cancel");
                first.connect();
                try (Socket peer = server.accept()) {
                    BufferedReader in = reader(peer);
                    handshake(in, "Alice");
                    send(peer, "PING :before welcome");
                    assertEquals("PONG :before welcome", in.readLine());
                    assertFalse(first.isRegistered());
                    send(peer, ":server 001 Alice :Welcome");
                    assertEquals("JOIN #keep correct-key", in.readLine());
                    CountDownLatch nick = new CountDownLatch(1);
                    first.addEventListener(e -> { if (e.getType() == SimpleIrcClient.IrcEvent.Type.NICK_CHANGE) nick.countDown(); });
                    send(peer, ":Alice!u@h JOIN #keep");
                    send(peer, ":Alice!u@h NICK :Bob");
                    assertTrue(nick.await(2, TimeUnit.SECONDS));
                    closed(first);
                }
                assertTrue(first.getChannels().isEmpty());
                SimpleIrcClient second = client(server, false).credentials(first.getConfirmedNick(), "test", "Test");
                try {
                    first.getDesiredChannels().forEach(second::joinChannel);
                    second.connect();
                    try (Socket peer = server.accept()) {
                        BufferedReader in = reader(peer);
                        handshake(in, "Bob");
                        send(peer, ":server 001 Bob :Welcome back");
                        assertEquals("JOIN #keep correct-key", in.readLine());
                        closed(second);
                    }
                } finally { second.disconnect(); }
            } finally { first.disconnect(); }
        }
    }

    @Test public void tlsHandshakeCanBeCancelledAndWorkersExit() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            SimpleIrcClient client = client(server, true);
            try {
                client.connect();
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(3000);
                    assertTrue(peer.getInputStream().read() >= 0); // ClientHello, deliberately unanswered.
                    assertFalse(client.isConnected());
                    closed(client);
                }
            } finally { client.disconnect(); }
        }
    }

    @Test public void earlyCancellationIsTerminalAndIdempotent() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            SimpleIrcClient client = client(server, false);
            AtomicInteger disconnects = new AtomicInteger();
            client.addEventListener(e -> { if (e.getType() == SimpleIrcClient.IrcEvent.Type.DISCONNECT) disconnects.incrementAndGet(); });
            client.disconnect();
            client.connect();
            closed(client);
            assertEquals(1, disconnects.get());
        }
    }

    @Test public void peerClosingDuringRegistrationReleasesWorkers() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            SimpleIrcClient client = client(server, false);
            CountDownLatch disconnected = new CountDownLatch(1);
            client.onDisconnected(disconnected::countDown);
            try {
                client.connect();
                try (Socket peer = server.accept()) { handshake(reader(peer), "Alice"); }
                assertTrue(disconnected.await(2, TimeUnit.SECONDS));
                closed(client);
            } finally { client.disconnect(); }
        }
    }

    @Test public void leavingCancelsJoinAlreadyWaitingInOutputQueue() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            SimpleIrcClient client = client(server, false);
            CountDownLatch ready = new CountDownLatch(1);
            client.addEventListener(e -> { if (e.getType() == SimpleIrcClient.IrcEvent.Type.REGISTERED) ready.countDown(); });
            try {
                client.connect();
                try (Socket peer = server.accept()) {
                    BufferedReader in = reader(peer);
                    handshake(in, "Alice");
                    send(peer, ":server 001 Alice :Welcome");
                    assertTrue(ready.await(2, TimeUnit.SECONDS));
                    client.sendMessage("Bob", "first");
                    assertEquals("PRIVMSG Bob :first", in.readLine());
                    client.joinChannel("#cancel", "key");
                    client.leaveChannel("#CANCEL");
                    client.sendMessage("Bob", "second");
                    assertEquals("PRIVMSG Bob :second", in.readLine());
                    assertTrue(client.getDesiredChannels().isEmpty());
                    closed(client);
                }
            } finally { client.disconnect(); }
        }
    }

    @Test public void nickFallbackObeysNicklenAndStopsAfterWelcome() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            SimpleIrcClient client = client(server, false).credentials("LongNickname", "test", "Test");
            try {
                client.connect();
                try (Socket peer = server.accept()) {
                    BufferedReader in = reader(peer);
                    handshake(in, "LongNickname");
                    send(peer, ":server 005 * NICKLEN=8 :supported");
                    send(peer, ":server 433 * LongNickname :In use");
                    assertEquals("NICK LongNi_1", in.readLine());
                    send(peer, ":server 001 LongNi_1 :Welcome");
                    send(peer, ":server 433 LongNi_1 Taken :In use");
                    send(peer, "PING :no automatic rename");
                    assertEquals("PONG :no automatic rename", in.readLine());
                    assertEquals("LongNi_1", client.getConfirmedNick());
                    closed(client);
                }
            } finally { client.disconnect(); }
        }
    }

    @Test public void adapterOnlyEchoesAfterSuccessfulSocketWrite() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
            IrcAdapter adapter = new IrcAdapter();
            List<IrcMessage> messages = new CopyOnWriteArrayList<>();
            CountDownLatch echo = new CountDownLatch(1);
            adapter.initialize(new IrcConfig() {
                @Override public String username() { return "Alice"; }
                @Override public String password() { return ""; }
            }, m -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                messages.add(m);
                if (m.getType() == IrcMessage.MessageType.PRIVATE) echo.countDown();
            }, null, "Alice");
            SimpleIrcClient client = adapter.getClient().server("127.0.0.1", server.getLocalPort(), false);
            try {
                adapter.sendMessage("#room", "offline");
                SwingUtilities.invokeAndWait(() -> { });
                assertFalse(messages.stream().anyMatch(m -> m.getType() == IrcMessage.MessageType.PRIVATE));
                CountDownLatch ready = new CountDownLatch(1);
                client.addEventListener(e -> { if (e.getType() == SimpleIrcClient.IrcEvent.Type.REGISTERED) ready.countDown(); });
                adapter.connect();
                try (Socket peer = server.accept()) {
                    BufferedReader in = reader(peer);
                    handshake(in, "Alice");
                    send(peer, ":server 001 Alice :Welcome");
                    assertTrue(ready.await(2, TimeUnit.SECONDS));
                    adapter.sendMessage("#room", "hello");
                    assertEquals("PRIVMSG #room :hello", in.readLine());
                    assertTrue(echo.await(2, TimeUnit.SECONDS));
                    assertTrue(messages.stream().anyMatch(m -> m.getType() == IrcMessage.MessageType.PRIVATE
                            && "hello".equals(m.getContent()) && "Alice".equals(m.getSender())));
                    closed(client);
                }
            } finally { adapter.clearPanel(); client.disconnect(); }
        }
    }

    @Test public void adapterDispatchesOnEdtAndDropsRetiredSessionCallbacks() throws Exception {
        IrcAdapter adapter = new IrcAdapter();
        List<IrcMessage> messages = new CopyOnWriteArrayList<>();
        adapter.initialize(new IrcConfig() {
            @Override public String username() { return "Alice"; }
            @Override public String password() { return ""; }
        },
                m -> { assertTrue(SwingUtilities.isEventDispatchThread()); messages.add(m); }, null, "Alice");
        adapter.getClient().processLine(":server 001 Alice :Welcome");
        SwingUtilities.invokeAndWait(() -> { });
        messages.clear();
        SwingUtilities.invokeAndWait(() -> {
            Thread reader = new Thread(() -> adapter.getClient().processLine(":bob!u@h PRIVMSG Alice :old session"));
            reader.start();
            try { reader.join(2000); } catch (InterruptedException e) { throw new AssertionError(e); }
            assertFalse(reader.isAlive());
            adapter.clearPanel(); // Invalidate before the queued event gets its EDT turn.
        });
        SwingUtilities.invokeAndWait(() -> { });
        assertTrue(messages.isEmpty());
        closed(adapter.getClient());
    }
}
