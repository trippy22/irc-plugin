package com.irc.protocol;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class SimpleIrcClient {
    private static final Pattern NUMERIC = Pattern.compile("^[0-9]{3}$");

    private volatile Socket transport;
    private final IrcOutput output = new IrcOutput();
    private static final java.util.concurrent.ScheduledThreadPoolExecutor CLOSER =
            new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "irc-close"); t.setDaemon(true); return t;
            });
    static { CLOSER.setRemoveOnCancelPolicy(true); }
    private boolean started;
    private volatile boolean registered;
    private boolean closed;
    private final java.util.concurrent.CompletableFuture<Void> closedFuture = new java.util.concurrent.CompletableFuture<>();
    private int nickRetries;
    private String requestedNick;
    @Getter private volatile String confirmedNick;
    private java.util.concurrent.ScheduledFuture<?> registrationDeadline;
    private BufferedWriter writer;
    private BufferedReader reader;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "irc-session"); t.setDaemon(true); return t; });
    private final List<IrcEventListener> listeners = new CopyOnWriteArrayList<>();

    @Getter
    private volatile String nick;
    private String username;
    private String realName;
    private String saslAccount;
    private String saslPassword;
    private boolean saslEnabled = false;
    private final Set<String> channels = new HashSet<>();
    private final Map<String, String> desiredChannels = new java.util.LinkedHashMap<>();
    private final ModeSpec modeSpec = ModeSpec.defaults();
    private final ChannelUserList channelUserList = new ChannelUserList(modeSpec);
    /**
     * LIST replies in flight. Mutated only inside a block synchronized on this list itself:
     * the reader thread appends rows on 322 and publishes on 323, while disconnect() (reachable
     * from the EDT via /quit) can clear it concurrently.
     */
    private final List<ChannelListEntry> channelListAccumulator = new ArrayList<>();
    /**
     * True from the first row of a run (321, or the first 322 if the server skips 321) until 323
     * or 263 closes it. Guarded by the channelListAccumulator lock.
     */
    private boolean channelListRunActive = false;
    /** The last completed LIST, published at 323. Immutable, safe to read from any thread. */
    private volatile List<ChannelListEntry> channelListSnapshot = Collections.emptyList();
    private volatile boolean channelListTruncated = false;
    private static final int CHANNEL_LIST_CAP = 20000;
    /** How long a silent socket is tolerated before the read fails. Also quoted in diagnostics. */
    private static final int READ_TIMEOUT_MS = 240000;

    private static final String REDACTED = "<redacted>";

    /**
     * Service commands whose remainder is a credential. Covers ChanServ as well as NickServ -
     * "/cs identify #chan <password>" is a channel password in plain sight - and treats the
     * PRIVMSG wrapper as optional so a bare "NS IDENTIFY" alias is caught too.
     *
     * Anchored, so it can only match a line we send: an incoming line starts with ":source",
     * which means ordinary conversation about identifying is never touched. Everything after the
     * verb goes, including ChanServ's channel argument - over-redacting one channel name is a
     * better trade than reasoning about each service's argument order.
     */
    private static final Pattern SERVICES_SECRET = Pattern.compile(
            "(?i)^(?:(?:PRIVMSG|NOTICE)\\s+)?(?:NickServ|ChanServ|NS|CS)\\s+:?\\s*"
                    + "(?:IDENTIFY|ID|LOGIN|AUTH|REGISTER|GHOST|RECOVER|RELEASE|DROP"
                    + "|SETPASS|SET(?:\\s+\\S+)?\\s+PASSWORD)\\b");

    private String host;
    private int port;
    private boolean secure;
    private volatile boolean connected = false;
    private volatile boolean shuttingDown = false;
    /**
     * Why the link went down, in the server's or the JDK's own words. Set by whichever path
     * noticed the failure and read by the DISCONNECT event, so "Disconnected from IRC" can say
     * what happened. Written on the reader thread, read from the EDT.
     */
    private volatile String disconnectReason = null;
    /** How far the current attempt got. Drives the wording of any failure we report. */
    private volatile ConnectPhase connectPhase = ConnectPhase.CONNECTING;
    /** Opt-in raw protocol logging, for diagnosing a failure we cannot reproduce. */
    private volatile boolean rawLogging = false;

    private final Map<String, List<IrcEvent>> activeBatches = new HashMap<>();
    private final Map<String, String> activeBatchChannels = new HashMap<>();

    private boolean capHistorySupported = false;
    private boolean capEndSent = false;
    private final Set<String> advertisedCaps = new HashSet<>();

    public SimpleIrcClient server(String host, int port, boolean secure) {
        this.host = host;
        this.port = port;
        this.secure = secure;
        return this;
    }

    public SimpleIrcClient credentials(String nick, String username, String realName) {
        this.nick = nick;
        this.requestedNick = nick;
        this.username = username;
        this.realName = realName;
        return this;
    }

    public void sasl(String account, String password) {
        this.saslAccount = account;
        this.saslPassword = password;
        this.saslEnabled = password != null && !password.isEmpty();
    }

    static String saslPlainResponse(String authcid, String password) {
        String payload = "\0" + authcid + "\0" + password;
        return Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void connect() {
        if (started || shuttingDown) return; // A client represents exactly one session.
        started = true;
        executor.submit(() -> {
            try {
                Socket tcp = new Socket();
                synchronized (this) {
                    if (shuttingDown) { tcp.close(); return; }
                    transport = tcp; // Publish before connect so cancellation can close it.
                }
                tcp.connect(new InetSocketAddress(host, port), 15000);
                tcp.setSoTimeout(READ_TIMEOUT_MS);
                Socket link = tcp;
                if (secure) {
                    connectPhase = ConnectPhase.TLS_HANDSHAKE;
                    SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                            .createSocket(tcp, host, port, true);
                    applyTlsSettings(tls);
                    tls.setSoTimeout(15000);
                    tls.startHandshake();
                    tls.setSoTimeout(READ_TIMEOUT_MS);
                    link = tls;
                }
                synchronized (this) {
                    if (shuttingDown) return;
                    writer = new BufferedWriter(new OutputStreamWriter(link.getOutputStream(), StandardCharsets.UTF_8));
                    reader = new BufferedReader(new InputStreamReader(link.getInputStream(), StandardCharsets.UTF_8));
                    connectPhase = ConnectPhase.REGISTERING;
                    connected = true;
                    executor.submit(() -> output.run(writer, this::writeFailed,
                            line -> { if (rawLogging) log.info("IRC >> {}", redactForLog(line)); }));
                    sendRawLine("CAP LS 302");
                    sendRawLine("NICK " + requestedNick);
                    sendRawLine("USER " + username + " 0 * :" + realName);
                    fireEvent(new IrcEvent(IrcEvent.Type.CONNECT, null, null, null, null));
                }
                synchronized (this) {
                    if (shuttingDown) return;
                    registrationDeadline = CLOSER.schedule(() -> {
                    if (!registered && !shuttingDown) {
                        recordDisconnectReason("Registration timed out after 30 seconds");
                        disconnect();
                    }
                    }, 30, java.util.concurrent.TimeUnit.SECONDS);
                }
                String line;
                while (!shuttingDown && (line = IrcLine.read(reader)) != null) processLine(line);
                if (!shuttingDown) recordDisconnectReason("Server closed the connection");
            } catch (Exception e) {
                if (!shuttingDown) {
                    String described = describeFailure(connectPhase, host, port, e);
                    recordDisconnectReason(described);
                    fireEvent(new IrcEvent(IrcEvent.Type.ERROR, null, null, described, null));
                }
            } finally {
                if (!shuttingDown) disconnect();
            }
        });
    }

    private void writeFailed(IOException failure) {
        if (shuttingDown) return;
        recordDisconnectReason("Write failed; queued messages may not have been delivered: " + failure.getMessage());
        disconnect();
    }
    /**
     * Turns on hostname verification, which SSLSocket does NOT do by default.
     *
     * A plain SSLSocket checks that the certificate chain is trusted, but not that the
     * certificate was issued for the host we asked for. Without endpoint identification any
     * CA-signed certificate - for any hostname at all - is accepted, which is exactly the
     * opening a man-in-the-middle needs on a hostile network. The credentials this client sends
     * during registration make that worth closing.
     *
     * Applied as a single SSLParameters update so the protocol list cannot be clobbered by
     * ordering between setSSLParameters and setEnabledProtocols.
     */
    static void applyTlsSettings(SSLSocket sslSocket) {
        SSLParameters params = sslSocket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        params.setProtocols(sslSocket.getSupportedProtocols());
        sslSocket.setSSLParameters(params);
    }

    public void disconnect() { disconnect(""); }

    public void onDisconnected(Runnable action) { closedFuture.thenRun(action); }

    public synchronized void disconnect(String reason) {
        if (shuttingDown) return;
        shuttingDown = true;
        registered = false;
        output.discardCommands();
        if (reason != null && !reason.isEmpty()) recordDisconnectReason(reason);
        // QUIT is best effort. A stalled write cannot delay cancellation beyond this deadline.
        CLOSER.schedule(this::finishClose, 250, java.util.concurrent.TimeUnit.MILLISECONDS);
        String quit = "QUIT :" + (reason == null || reason.isEmpty() ? "Disconnecting" : reason);
        if (!connected || !validLine(quit) || !output.offer(quit, true, this::finishClose)) finishClose();
    }

    private synchronized void finishClose() {
        if (closed) return;
        closed = true;
        if (registrationDeadline != null) registrationDeadline.cancel(false);
        connected = false;
        output.close();
        // Close the underlying transport first: never acquire BufferedReader/Writer locks
        // while another worker is blocked in a read or TLS write.
        if (transport != null) try { transport.close(); } catch (IOException ignored) { }
        executor.shutdownNow();
        activeBatches.clear();
        activeBatchChannels.clear();
        List<String> joined = new ArrayList<>(channels);
        channels.clear();
        channelUserList.clear();
        resetChannelListRun();
        for (String channel : joined) fireUsersChanged(channel);
        fireEvent(new IrcEvent(IrcEvent.Type.DISCONNECT, null, null, disconnectReason, String.join(",", joined)));
        closedFuture.complete(null);
    }

    public synchronized Set<String> getChannels() {
        return Collections.unmodifiableSet(new HashSet<>(channels));
    }

    public boolean isRegistered() { return registered && !shuttingDown; }

    public synchronized boolean sameName(String a, String b) {
        return a != null && b != null && modeSpec.fold(a).equals(modeSpec.fold(b));
    }

    public String getCaseMapping() { return modeSpec.caseMapping(); }

    public static boolean validChannel(String channel) {
        return isChannel(channel) && channel.length() > 1
                && channel.chars().noneMatch(c -> c <= 32 || c == ',' || c == 127);
    }

    private void channelState(String channel, String state, String detail) {
        fireEvent(new IrcEvent(IrcEvent.Type.CHANNEL_STATE, null, channel, state, detail));
    }

    public static boolean isChannel(String name) {
        return name != null && !name.isEmpty() && "#&+!".indexOf(name.charAt(0)) >= 0;
    }

    public synchronized Map<String, String> getDesiredChannels() {
        return Collections.unmodifiableMap(new java.util.LinkedHashMap<>(desiredChannels));
    }

    public synchronized void joinChannel(String channel, String password) {
        if (!validChannel(channel)) {
            commandError("Invalid channel name: " + channel);
            return;
        }
        desiredChannels.keySet().removeIf(name -> sameName(name, channel));
        desiredChannels.put(channel, password == null ? "" : password);
        if (shuttingDown) {
            commandError("Channel saved for reconnect; currently disconnected.");
            return;
        }
        if (registered) sendJoin(channel);
        else channelState(channel, "WAITING", "Waiting for registration");
        // Registration drains the desired map once, so part-before-welcome cancels the join.
    }

    private void sendJoin(String channel) {
        String password = desiredChannels.get(channel);
        String command = "JOIN " + channel + (password == null || password.isEmpty() ? "" : " " + password);
        output.cancelMatching(key -> sameName(key, channel));
        if (validLine(command) && output.offer(command, false, null, channel)) {
            channelState(channel, "JOINING", "Waiting for the server to confirm JOIN");
        } else {
            channelState(channel, "FAILED", "JOIN could not be queued; retry joining this channel");
            commandError("JOIN " + channel + " could not be queued; check the channel key or output queue.");
        }
    }

    public void leaveChannel(String channel) { leaveChannel(channel, null); }

    public synchronized void leaveChannel(String channel, String reason) {
        desiredChannels.keySet().removeIf(name -> sameName(name, channel));
        boolean cancelled = output.cancelMatching(key -> sameName(key, channel));
        boolean joined = channels.stream().anyMatch(name -> sameName(name, channel));
        if (registered && (!cancelled || joined)) {
            channelState(channel, "LEAVING", "Waiting for the server to confirm PART");
            // Membership cleanup must remain possible when the ordinary command queue is full.
            enqueue("PART " + channel + (reason == null || reason.isEmpty() ? "" : " :" + reason), true, null);
        } else channelState(channel, "OFFLINE", "Join cancelled");
    }

    public void sendMessage(String target, String message) { sendMessage(target, message, null); }
    public boolean sendMessage(String target, String message, Runnable sent) {
        return sendCommand("PRIVMSG " + target + " :" + message, sent);
    }
    public void sendAction(String target, String action) { sendAction(target, action, null); }
    public boolean sendAction(String target, String action, Runnable sent) {
        return sendCommand("PRIVMSG " + target + " :\u0001ACTION " + action + "\u0001", sent);
    }
    public void sendNotice(String target, String message) { sendNotice(target, message, null); }
    public boolean sendNotice(String target, String message, Runnable sent) {
        return sendCommand("NOTICE " + target + " :" + message, sent);
    }
    public void setNick(String newNick) {
        // The server is authoritative; a rejected request must not change nick.
        sendCommand("NICK " + newNick, null);
    }

    public synchronized boolean sendCommand(String line, Runnable sent) {
        if (!isRegistered()) {
            commandError("Not registered with IRC; command was not sent.");
            return false;
        }
        return enqueue(line, false, sent);
    }

    /** Internal registration/keepalive traffic bypasses the paced command queue. */
    public synchronized void sendRawLine(String line) {
        if (!shuttingDown) enqueue(line, true, null);
    }

    private boolean enqueue(String line, boolean urgent, Runnable sent) {
        if (!validLine(line)) {
            commandError("Command rejected: use one line of at most 510 UTF-8 bytes.");
            return false;
        }
        if (!output.offer(line, urgent, sent)) {
            commandError("IRC output queue is full or closed; command was not sent.");
            if (urgent) disconnect("Protocol output queue exhausted");
            return false;
        }
        return true;
    }

    private static boolean validLine(String line) {
        return line != null && !line.isEmpty() && line.indexOf('\r') < 0 && line.indexOf('\n') < 0
                && line.indexOf('\0') < 0 && line.getBytes(StandardCharsets.UTF_8).length <= 510;
    }

    private void commandError(String text) {
        fireEvent(new IrcEvent(IrcEvent.Type.SERVER_ERROR, null, null, text, null));
    }
    synchronized void processLine(String line) {
        if (shuttingDown) return;
        if (rawLogging) log.info("IRC << {}", redactForLog(line));
        IrcLine decoded = IrcLine.parse(line);
        if (decoded == null) return;
        if ("PING".equals(decoded.command)) {
            sendRawLine("PONG :" + (decoded.params.isEmpty() ? "" : decoded.params.get(decoded.params.size() - 1)));
            return;
        }
        processCommand(decoded);
    }
    private void processCommand(IrcLine line) {
        String source = line.source;
        String command = line.command;
        List<String> params = line.params;
        String currentTagTime = line.tags.get("time");
        String currentTagBatch = line.tags.get("batch");
        String sourceNick = extractNick(source);

        // IRCv3 batch intercept: accumulate tagged messages instead of processing normally
        if (currentTagBatch != null && activeBatches.containsKey(currentTagBatch)) {
            String batchRef = currentTagBatch;
            if (activeBatches.get(batchRef).size() >= 1000) return;
            if (command.equalsIgnoreCase("PRIVMSG") && params.size() >= 2) {
                String target = params.get(0);
                String msgBody = params.get(1);
                if (msgBody.length() >= 2 && msgBody.startsWith("\u0001") && msgBody.endsWith("\u0001")) {
                    // CTCP — check for ACTION
                    String ctcp = msgBody.substring(1, msgBody.length() - 1);
                    String[] parts = ctcp.split(" ", 2);
                    if ("ACTION".equals(parts[0])) {
                        String actionText = parts.length > 1 ? parts[1] : "";
                        activeBatches.get(batchRef).add(
                            new IrcEvent(IrcEvent.Type.ACTION, sourceNick, target, actionText, currentTagTime)
                        );
                    }
                } else {
                    activeBatches.get(batchRef).add(
                        new IrcEvent(IrcEvent.Type.MESSAGE, sourceNick, target, msgBody, currentTagTime)
                    );
                }
                return;
            }
            // Non-PRIVMSG commands in a chathistory batch are silently ignored
            return;
        }

        switch (command.toUpperCase()) {
            case "PRIVMSG":
                if (params.size() >= 2) {
                    String target = params.get(0);
                    String message = params.get(1);

                    if (message.length() >= 2 && message.startsWith("\u0001") && message.endsWith("\u0001")) {
                        handleCtcp(source, target, message);
                    } else {
                        String messageChannel = isChannel(target) ? target : sourceNick;
                        fireEvent(new IrcEvent(IrcEvent.Type.MESSAGE, sourceNick, messageChannel, message, null));
                    }
                }
                break;

            case "JOIN":
                if (!params.isEmpty()) {
                    String channel = params.get(0);
                    if (sameName(sourceNick, nick)) {
                        channels.add(channel);
                        channelState(channel, "JOINED", "");
                    }
                    fireEvent(new IrcEvent(IrcEvent.Type.JOIN, sourceNick, channel, null, null));
                    channelUserList.join(channel, sourceNick);
                    fireUsersChanged(channel);
                    if (sameName(sourceNick, nick) && capHistorySupported) {
                        sendCommand("CHATHISTORY LATEST " + channel + " * 100", null);
                    }
                }
                break;

            case "PART":
                if (!params.isEmpty()) {
                    String channel = params.get(0);
                    String reason = params.size() > 1 ? params.get(1) : "";

                    if (!sameName(sourceNick, nick)) {
                        fireEvent(new IrcEvent(IrcEvent.Type.PART, sourceNick, channel, reason, null));
                        channelUserList.part(channel, sourceNick);
                        fireUsersChanged(channel);
                    } else {
                        channels.removeIf(joined -> sameName(joined, channel));
                        channelUserList.removeChannel(channel);
                        channelState(channel, desiredChannels.keySet().stream().anyMatch(c -> sameName(c, channel))
                                ? "JOINING" : "OFFLINE", reason);
                        fireUsersChanged(channel);
                    }
                }
                break;

            case "QUIT":
                String quitMessage = params.isEmpty() ? "" : params.get(0);

                List<String> userChannels = channelUserList.quit(sourceNick);
                fireEvent(new IrcEvent(IrcEvent.Type.QUIT, sourceNick, null, quitMessage, String.join(",", userChannels)));
                for (String quitChannel : userChannels) {
                    fireUsersChanged(quitChannel);
                }
                break;

            case "NICK":
                if (!params.isEmpty()) {
                    String newNick = params.get(0);
                    if (sameName(sourceNick, this.nick)) {
                        this.nick = newNick;
                        confirmedNick = newNick;
                    }

                    userChannels = channelUserList.rename(sourceNick, newNick);
                    fireEvent(new IrcEvent(IrcEvent.Type.NICK_CHANGE, sourceNick, null, newNick, String.join(",", userChannels)));
                    for (String renamedChannel : userChannels) {
                        fireUsersChanged(renamedChannel);
                    }
                }
                break;

            case "KICK":
                if (params.size() >= 2) {
                    String channel = params.get(0);
                    String kickedUser = params.get(1);
                    String kickMessage = params.size() > 2 ? params.get(2) : "";

                    fireEvent(new IrcEvent(IrcEvent.Type.KICK, sourceNick, channel, kickedUser + " " + kickMessage, null));
                    if (!sameName(kickedUser, nick)) {
                        channelUserList.kick(channel, kickedUser);
                        fireUsersChanged(channel);
                    } else {
                        channels.removeIf(joined -> sameName(joined, channel));
                        channelUserList.removeChannel(channel);
                        channelState(channel, "KICKED", kickMessage);
                        fireUsersChanged(channel);
                    }
                }
                break;

            case "NOTICE":
                if (params.size() >= 2) {
                    String target = params.get(0);
                    String message = params.get(1);
                    if (source.contains("!")) {
                        fireEvent(new IrcEvent(IrcEvent.Type.NOTICE, sourceNick, target, message, null));
                    } else {
                        fireEvent(new IrcEvent(IrcEvent.Type.SERVER_NOTICE, source, null, message, null));
                    }
                }
                break;

            case "MODE":
                if (params.size() >= 2) {
                    String target = params.get(0);
                    StringBuilder modeString = new StringBuilder();
                    for (int i = 1; i < params.size(); i++) {
                        modeString.append(" ").append(params.get(i));
                    }

                    if (isChannel(target)) {
                        fireEvent(new IrcEvent(IrcEvent.Type.CHANNEL_MODE, "* " + sourceNick + " sets mode(s)", target, modeString.toString().trim(), null));
                        channelUserList.applyModeChange(target, params.subList(1, params.size()));
                        fireUsersChanged(target);
                    } else {
                        fireEvent(new IrcEvent(IrcEvent.Type.USER_MODE, sourceNick, target, modeString.toString().trim(), null));
                    }
                }
                break;

            case "TOPIC":
                if (params.size() >= 2) {
                    String channel = params.get(0);
                    String topic = params.get(1);
                    fireEvent(new IrcEvent(IrcEvent.Type.TOPIC, sourceNick, channel, topic, null));
                }
                break;

            case "KILL":
                // Only our own KILL ends our link; another user's is not our disconnect.
                if (params.isEmpty() || !sameName(params.get(0), nick)) break;
                String killReason = params.size() >= 2 ? params.get(params.size() - 1) : "";
                recordDisconnectReason("killed by " + sourceNick
                        + (killReason.isEmpty() ? "" : ": " + killReason));
                fireEvent(new IrcEvent(IrcEvent.Type.ERROR, null, null,
                        "Killed by " + sourceNick
                                + (killReason.isEmpty() ? "" : ": " + killReason), null));
                disconnect();
                break;

            case "ERROR":
                // The server's last word before it drops the link - ping timeout, K-line, Killed.
                // Dropping it left the user with a bare "Disconnected from IRC" and nothing to
                // report, so this text is the single most valuable line the connection produces.
                String serverError = params.isEmpty() ? "server sent ERROR without a reason"
                        : params.get(params.size() - 1);
                recordDisconnectReason(serverError);
                fireEvent(new IrcEvent(IrcEvent.Type.ERROR, null, null,
                        "Server closed the link: " + serverError, null));
                disconnect();
                break;

            case "BATCH":
                if (params.isEmpty()) break;
                String batchToken = params.get(0);
                if (batchToken.startsWith("+")) {
                    if (activeBatches.size() >= 16) {
                        commandError("Too many unfinished history batches; closing the connection.");
                        disconnect();
                        break;
                    }
                    String ref = batchToken.substring(1);
                    // params = [+ref, type, channel]
                    String batchChannel = params.size() >= 3 ? params.get(2) : "";
                    activeBatches.put(ref, new ArrayList<>());
                    activeBatchChannels.put(ref, batchChannel);
                } else if (batchToken.startsWith("-")) {
                    String ref = batchToken.substring(1);
                    List<IrcEvent> accumulated = activeBatches.remove(ref);
                    String batchChannel = activeBatchChannels.remove(ref);
                    if (accumulated != null && batchChannel != null) {
                        fireEvent(new IrcEvent(IrcEvent.Type.HISTORY_BATCH, null, batchChannel, null, null, accumulated));
                    }
                }
                break;

            case "CAP":
                if (params.size() < 2) break;
                String capSubCommand = params.get(1).toUpperCase();
                switch (capSubCommand) {
                    case "LS": {
                        // Check for multi-line continuation: params = [clientNick, LS, *, cap-list] vs [clientNick, LS, cap-list]
                        boolean isContinuation = params.size() >= 4 && "*".equals(params.get(2));
                        String capList = isContinuation ? params.get(3) : (params.size() >= 3 ? params.get(2) : "");
                        for (String cap : capList.split(" ")) {
                            if (!cap.isEmpty()) {
                                int eq = cap.indexOf('=');
                                advertisedCaps.add(eq > 0 ? cap.substring(0, eq) : cap);
                            }
                        }
                        if (!isContinuation) {
                            // Final LS line: decide what to request
                            List<String> toRequest = new ArrayList<>();
                            if (advertisedCaps.contains("chathistory")) toRequest.add("chathistory");
                            if (advertisedCaps.contains("batch")) toRequest.add("batch");
                            if (advertisedCaps.contains("server-time")) toRequest.add("server-time");
                            if (saslEnabled && advertisedCaps.contains("sasl")) toRequest.add("sasl");
                            if (!toRequest.isEmpty()) {
                                sendRawLine("CAP REQ :" + String.join(" ", toRequest));
                            } else if (!capEndSent) {
                                sendRawLine("CAP END");
                                capEndSent = true;
                            }
                        }
                        break;
                    }
                    case "ACK": {
                        String acked = params.size() >= 3 ? params.get(2) : "";
                        boolean saslAcked = false;
                        for (String cap : acked.split(" ")) {
                            String c = cap.trim();
                            if ("chathistory".equals(c)) capHistorySupported = true;
                            if ("sasl".equals(c)) saslAcked = true;
                        }
                        if (saslAcked) {
                            // Begin SASL PLAIN; hold CAP END until the SASL exchange completes
                            // (RPL_SASLSUCCESS or an error numeric).
                            sendRawLine("AUTHENTICATE PLAIN");
                        } else if (!capEndSent) {
                            sendRawLine("CAP END");
                            capEndSent = true;
                        }
                        break;
                    }
                    case "NAK":
                        capHistorySupported = false;
                        if (!capEndSent) {
                            sendRawLine("CAP END");
                            capEndSent = true;
                        }
                        break;
                }
                break;

            case "AUTHENTICATE":
                // Server replies "AUTHENTICATE +" when ready for the SASL PLAIN response.
                if (saslEnabled && !params.isEmpty() && "+".equals(params.get(0))) {
                    String authcid = (saslAccount != null && !saslAccount.isEmpty()) ? saslAccount : nick;
                    String payload = saslPlainResponse(authcid, saslPassword);
                    for (int offset = 0; offset < payload.length(); offset += 400) {
                        sendRawLine("AUTHENTICATE " + payload.substring(offset, Math.min(offset + 400, payload.length())));
                    }
                    if (payload.length() % 400 == 0) sendRawLine("AUTHENTICATE +");
                }
                break;

            default:
                if (NUMERIC.matcher(command).matches()) {
                    int numeric = Integer.parseInt(command);
                    handleNumeric(numeric, source, params);
                }
                break;
        }
    }

    private void handleCtcp(String source, String target, String message) {
        String ctcp = message.substring(1, message.length() - 1);
        String[] parts = ctcp.split(" ", 2);
        String command = parts[0].toUpperCase();
        String param = parts.length > 1 ? parts[1] : "";
        String sourceNick = extractNick(source);

        switch (command) {
            case "ACTION":
                String actionChannel = isChannel(target) ? target : sourceNick;
                fireEvent(new IrcEvent(IrcEvent.Type.ACTION, sourceNick, actionChannel, param, null));
                break;
            case "VERSION":
                sendCommand("NOTICE " + sourceNick + " :\u0001VERSION RuneLite IRC Plugin\u0001", null);
                break;
            case "PING":
                sendCommand("NOTICE " + sourceNick + " :\u0001PING " + param + "\u0001", null);
                break;
        }
    }

    private void handleNumeric(int numeric, String source, List<String> params) {
        switch (numeric) {
            case 1:
                // RPL_WELCOME: the server states our final, authoritative nick here.
                // This is how we learn our real nick after a 433/nick-in-use retry, as
                // the server does not echo a NICK during registration.
                if (!params.isEmpty()) {
                    nick = params.get(0);
                    confirmedNick = nick;
                }
                connected = true;
                connectPhase = ConnectPhase.ESTABLISHED;
                fireEvent(new IrcEvent(IrcEvent.Type.REGISTERED, null, null, null, null));
                break;
            case 5: // RPL_ISUPPORT
                if (params.size() >= 2) {
                    modeSpec.applyIsupport(params.subList(1, params.size()));
                    channelUserList.reindex();
                    fireEvent(new IrcEvent(IrcEvent.Type.SERVER_SUPPORT, null, null, null, null));
                }
                break;
            case 263: // RPL_TRYAGAIN: only a throttled LIST concerns us here.
                if (params.size() >= 2 && "LIST".equalsIgnoreCase(params.get(1))) {
                    synchronized (channelListAccumulator) {
                        channelListAccumulator.clear();
                        channelListRunActive = false;
                    }
                    channelListTruncated = false;
                    fireEvent(new IrcEvent(IrcEvent.Type.CHANNEL_LIST_FAILED, null, null,
                            params.size() >= 3 ? params.get(params.size() - 1)
                                    : "Server asked us to try again later", null));
                }
                break;
            case 301:
                if (params.size() >= 3)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), String.format("%s is away: %s", params.get(1), params.get(2)), null));
                break;
            case 311:
                if (params.size() >= 6)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), String.format("%s is %s@%s (%s)", params.get(1), params.get(2), params.get(3), params.get(5)), null));
                break;
            case 312:
                if (params.size() >= 4)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), String.format("%s is connected to %s (%s)", params.get(1), params.get(2), params.get(3)), null));
                break;
            case 313:
                if (params.size() >= 2)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), params.get(1) + " is an IRC operator", null));
                break;
            case 317:
                if (params.size() >= 3)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), String.format("%s has been idle for %s seconds", params.get(1), params.get(2)), null));
                break;
            case 318:
                if (params.size() >= 2)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), "End of WHOIS for " + params.get(1), null));
                break;
            case 319:
                if (params.size() >= 3)
                    fireEvent(new IrcEvent(IrcEvent.Type.WHOIS_REPLY, "System", params.get(1), String.format("%s is on channels: %s", params.get(1), params.get(2)), null));
                break;
            case 321: // RPL_LISTSTART: an explicit start always resets the run.
                synchronized (channelListAccumulator) {
                    channelListAccumulator.clear();
                    channelListRunActive = true;
                }
                channelListTruncated = false;
                break;
            case 322: // RPL_LIST: <me> <channel> <count> :<topic>
                if (params.size() >= 3) {
                    String listChannel = params.get(1);
                    if (listChannel != null && !listChannel.isEmpty()) {
                        synchronized (channelListAccumulator) {
                            if (!channelListRunActive) {
                                // Some servers skip 321; the first row starts the run and must
                                // not inherit a stale truncation flag from the previous one.
                                channelListAccumulator.clear();
                                channelListRunActive = true;
                                channelListTruncated = false;
                            }
                            if (channelListAccumulator.size() >= CHANNEL_LIST_CAP) {
                                channelListTruncated = true;
                            } else {
                                int userCount;
                                try {
                                    userCount = Integer.parseInt(params.get(2).trim());
                                } catch (NumberFormatException e) {
                                    // One odd row costs its count, not the rest of the list.
                                    userCount = 0;
                                }
                                String listTopic = params.size() >= 4 && params.get(3) != null
                                        ? params.get(3) : "";
                                channelListAccumulator.add(
                                        new ChannelListEntry(listChannel, userCount, listTopic));
                            }
                        }
                    }
                }
                break;
            case 323: { // RPL_LISTEND: publish one immutable snapshot, but only for a real run -
                         // an unpaired 323 must not clobber the previous snapshot.
                List<ChannelListEntry> completedRun = null;
                synchronized (channelListAccumulator) {
                    if (channelListRunActive) {
                        completedRun = new ArrayList<>(channelListAccumulator);
                        channelListAccumulator.clear();
                        channelListRunActive = false;
                    }
                }
                if (completedRun != null) {
                    channelListSnapshot = Collections.unmodifiableList(completedRun);
                    fireEvent(new IrcEvent(IrcEvent.Type.CHANNEL_LIST, null, null, null, null));
                }
                break;
            }
            case 324:
                if (params.size() >= 2) {
                    String target = params.get(1);
                    StringBuilder message = new StringBuilder();
                    for (int i = 2; i < params.size(); i++) message.append(" ").append(params.get(i));
                    fireEvent(new IrcEvent(IrcEvent.Type.CHANNEL_MODE, "* Modes", target, message.toString().trim(), null));
                }
                break;
            case 332:
                if (params.size() >= 3)
                    fireEvent(new IrcEvent(IrcEvent.Type.TOPIC, "System", params.get(1), params.get(2), null));
                break;
            case 333:
                if (params.size() >= 4)
                    fireEvent(new IrcEvent(IrcEvent.Type.TOPIC_INFO, "* Topic set by", params.get(1), params.get(2), null));
                break;
            case 353:
                if (params.size() >= 4) {
                    String channel = params.get(2);
                    String[] users = params.get(3).split(" ");
                    channelUserList.addNames(channel, java.util.Arrays.asList(users));
                    fireEvent(new IrcEvent(IrcEvent.Type.NAMES, null, channel, String.join(" ", users), null));
                }
                break;
            case 366: // RPL_ENDOFNAMES
                if (params.size() >= 2) {
                    String channel = params.get(1);
                    channelUserList.endNames(channel);
                    fireUsersChanged(channel);
                }
                break;
            case 431:
                commandError("Server error 431: a nickname is required.");
                if (!registered) disconnect("Registration failed: choose a nickname and reconnect");
                break;
            case 432:
            case 433:
            case 436:
            case 437:
                String rejected = params.size() >= 2 ? params.get(1) : requestedNick;
                String detail = params.size() >= 3 ? params.get(params.size() - 1) : "Nickname rejected";
                if (numeric == 433 && !registered && nickRetries < 5) {
                    String suffix = "_" + (++nickRetries);
                    requestedNick = nick.substring(0,
                            Math.min(nick.length(), Math.max(1, modeSpec.nickLength() - suffix.length()))) + suffix;
                    commandError("Nickname " + rejected + " is in use. Trying " + requestedNick + ".");
                    sendRawLine("NICK " + requestedNick);
                } else {
                    commandError("Server error " + numeric + " for " + rejected + ": " + detail);
                    if (!registered) disconnect("Registration failed: choose another nickname and reconnect");
                }
                break;
            case 475:
                if (params.size() >= 3) {
                    channelState(params.get(1), "FAILED", params.get(2));
                    fireEvent(new IrcEvent(IrcEvent.Type.BAD_CHANNEL_KEY, null, params.get(1), params.get(2), null));
                }
                break;
            case 903: // RPL_SASLSUCCESS
                if (!capEndSent) {
                    sendRawLine("CAP END");
                    capEndSent = true;
                }
                fireEvent(new IrcEvent(IrcEvent.Type.SASL_SUCCESS, null, null,
                        params.size() >= 2 ? params.get(params.size() - 1) : "SASL authentication successful", null));
                break;
            case 902: // ERR_NICKLOCKED
            case 904: // ERR_SASLFAIL
            case 905: // ERR_SASLTOOLONG
            case 906: // ERR_SASLABORTED
            case 908: // RPL_SASLMECHS
                // Authentication failed; end capability negotiation so registration can proceed
                // unauthenticated rather than stalling.
                if (!capEndSent) {
                    sendRawLine("CAP END");
                    capEndSent = true;
                }
                fireEvent(new IrcEvent(IrcEvent.Type.SASL_FAILED, null, null,
                        params.size() >= 2 ? params.get(params.size() - 1) : "SASL authentication failed", null));
                break;
            default:
                // Every numeric we do not name above used to be discarded, which is how a refused
                // connection became a silent one. The error ranges always carry text the user
                // needs - "you are banned", "reconnecting too fast", "erroneous nickname" - so
                // report it verbatim rather than enumerating every numeric a server might send.
                if (numeric >= 400 && numeric <= 599 && params.size() >= 2) {
                    String errorText = String.join(" ", params.subList(1, params.size()));
                    if ((numeric == 403 || numeric == 405 || numeric == 471 || numeric == 473
                            || numeric == 474 || numeric == 476 || numeric == 477 || numeric == 489)
                            && isChannel(params.get(1))) channelState(params.get(1), "FAILED", errorText);
                    if (errorText != null && !errorText.isEmpty()) {
                        fireEvent(new IrcEvent(IrcEvent.Type.SERVER_ERROR, null, null,
                                "Server error " + numeric + ": " + errorText, null));
                        if (!registered && (numeric == 463 || numeric == 464 || numeric == 465)) {
                            disconnect("Registration rejected: " + errorText);
                        }
                    }
                }
                break;
        }
    }

    /**
     * Records the first reason seen for a teardown. First writer wins: a server ERROR explains a
     * drop better than the EOF that follows it a moment later.
     */
    private synchronized void recordDisconnectReason(String reason) {
        if (reason == null || reason.isEmpty()) return;
        if (disconnectReason == null) disconnectReason = reason;
    }

    /**
     * Strips credentials from a line before it reaches the log. Raw logging exists so a user can
     * send us the log, which makes this a correctness requirement rather than a nicety.
     *
     * What is kept matters as much as what is removed: the SASL mechanism and the server's
     * "AUTHENTICATE +" continuation carry no secret and are exactly what you need to see to
     * diagnose a stalled negotiation.
     */
    static String redactForLog(String line) {
        if (line == null || line.isEmpty()) return line;

        if (line.regionMatches(true, 0, "AUTHENTICATE ", 0, 13)) {
            String payload = line.substring(13).trim();
            boolean negotiation = payload.equals("+")
                    || payload.equalsIgnoreCase("PLAIN")
                    || payload.equalsIgnoreCase("EXTERNAL");
            return negotiation ? line : "AUTHENTICATE " + REDACTED;
        }

        if (line.regionMatches(true, 0, "PASS ", 0, 5)) {
            return "PASS " + REDACTED;
        }

        Matcher services = SERVICES_SECRET.matcher(line);
        if (services.find()) {
            return line.substring(0, services.end()) + " " + REDACTED;
        }

        if (line.regionMatches(true, 0, "JOIN ", 0, 5)) {
            // "JOIN <channels> [keys]" - a third token is always the key list.
            String[] tokens = line.split(" ");
            if (tokens.length >= 3) {
                return tokens[0] + " " + tokens[1] + " " + REDACTED;
            }
            return line;
        }

        return redactModeKeys(line);
    }

    /**
     * Redacts channel keys from MODE traffic, in both directions: "MODE #chan +k secret" going
     * out, and ":op MODE #chan -k secret" or the server's "324 me #chan +nk secret" coming back.
     * Removing a key names it on most ircds, so -k leaks as readily as +k.
     *
     * Rather than track which mode letters consume a parameter - which varies by ircd and would
     * have to stay in step with ISUPPORT - every parameter after a mode string containing 'k' is
     * dropped. Over-redacting a mode argument costs nothing; the mode string itself, which is the
     * part worth reading, is kept.
     */
    private static String redactModeKeys(String line) {
        String[] tokens = line.split(" ");
        int command = tokens.length > 0 && tokens[0].startsWith(":") ? 1 : 0;
        if (command >= tokens.length) return line;

        if (!tokens[command].equalsIgnoreCase("MODE") && !tokens[command].equals("324")) {
            return line;
        }

        for (int i = command + 1; i < tokens.length; i++) {
            boolean isModeSpec = tokens[i].startsWith("+") || tokens[i].startsWith("-");
            // Mode letters are case sensitive: 'K' is a different mode on some ircds.
            if (isModeSpec && tokens[i].indexOf('k') >= 0) {
                if (i + 1 >= tokens.length) return line;
                return String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, i + 1))
                        + " " + REDACTED;
            }
        }
        return line;
    }

    /** Where a connection was when it failed. Shapes the message the user is shown. */
    enum ConnectPhase {
        CONNECTING("connecting to"),
        TLS_HANDSHAKE("completing the TLS handshake with"),
        REGISTERING("registering with"),
        ESTABLISHED("reading from");

        final String description;

        ConnectPhase(String description) {
            this.description = description;
        }
    }

    /**
     * Turns an exception into something a user can paste into a bug report. The JDK's own messages
     * are too terse to act on: UnknownHostException says only the hostname, and SocketTimeoutException
     * says only "Read timed out" with no hint that four minutes of silence went by.
     */
    static String describeFailure(ConnectPhase phase, String host, int port, Throwable e) {
        String where = host + ":" + port;
        String type = e.getClass().getSimpleName();
        String detail = e.getMessage();

        if (e instanceof UnknownHostException) {
            return "DNS lookup failed: " + host + " could not be resolved (" + type + ")";
        }
        if (e instanceof SocketTimeoutException) {
            int seconds = phase == ConnectPhase.CONNECTING || phase == ConnectPhase.TLS_HANDSHAKE
                    ? 15 : READ_TIMEOUT_MS / 1000;
            return "Timed out " + phase.description + " " + where + " after " + seconds + "s (" + type + ")";
        }
        return "Failed while " + phase.description + " " + where + " - " + type
                + (detail == null || detail.isEmpty() ? "" : ": " + detail);
    }

    private String extractNick(String source) {
        if (source == null || source.isEmpty()) return "";
        int exclamation = source.indexOf('!');
        return exclamation > 0 ? source.substring(0, exclamation) : source;
    }

    /**
     * Mirrors every protocol line to the client log, credentials stripped. Off by default and
     * safe to flip mid-session, which matters because the failure worth capturing usually
     * happens during connect.
     */
    public void setRawLogging(boolean enabled) {
        this.rawLogging = enabled;
    }

    public void addEventListener(IrcEventListener listener) {
        listeners.add(listener);
    }

    public void removeEventListener(IrcEventListener listener) {
        listeners.remove(listener);
    }

    /** Current roster for a channel, sorted by rank then nick. Empty when unknown. */
    public synchronized List<ChannelUserList.Entry> getChannelUsers(String channel) {
        return channelUserList.snapshot(channel);
    }

    /** The last completed channel list. Immutable; empty until a LIST finishes. */
    public List<ChannelListEntry> getChannelListSnapshot() {
        return channelListSnapshot;
    }

    /** True when the last LIST hit CHANNEL_LIST_CAP and rows were dropped. */
    public boolean isChannelListTruncated() {
        return channelListTruncated;
    }

    /**
     * Clears any abandoned LIST run before a fresh request goes out. Without this, a run that
     * lost its 323 (the server's reply is cut short, but the connection survives) leaves
     * channelListRunActive true, so the next LIST's 322 rows would append onto the stale ones
     * instead of starting fresh. Does not touch channelListSnapshot - the previously displayed
     * list should survive until a new one completes, so Refresh does not blank the dialog
     * mid-flight.
     */
    public void resetChannelListRun() {
        synchronized (channelListAccumulator) {
            channelListAccumulator.clear();
            channelListRunActive = false;
        }
        channelListTruncated = false;
    }

    /** Transport is open; use isRegistered() to determine whether commands are allowed. */
    public boolean isConnected() {
        return connected;
    }

    /** Fires USERS_CHANGED for a channel whose roster just changed. */
    private void fireUsersChanged(String channel) {
        fireEvent(new IrcEvent(IrcEvent.Type.USERS_CHANGED, null, channel, null, null));
    }

    private void fireEvent(IrcEvent event) {
        if (event.getType() == IrcEvent.Type.REGISTERED) {
            if (registered) return;
            registered = true;
            if (registrationDeadline != null) registrationDeadline.cancel(false);
            for (String channel : new ArrayList<>(desiredChannels.keySet())) sendJoin(channel);
        }
        for (IrcEventListener listener : listeners) {
            try { listener.onEvent(event); }
            catch (RuntimeException e) { log.warn("IRC event listener failed", e); }
        }
    }
    public interface IrcEventListener {
        void onEvent(IrcEvent event);
    }

    @Getter
    public static class IrcEvent {
        public enum Type {
            CONNECT, DISCONNECT, REGISTERED, MESSAGE, ACTION, JOIN, PART, QUIT,
            NICK_CHANGE, KICK, NOTICE, SERVER_NOTICE, CHANNEL_MODE, USER_MODE,
            TOPIC, NAMES, ERROR, TOPIC_INFO, BAD_CHANNEL_KEY, WHOIS_REPLY,
            HISTORY_BATCH, SASL_SUCCESS, SASL_FAILED, USERS_CHANGED,
            CHANNEL_LIST, CHANNEL_LIST_FAILED, SERVER_ERROR, CHANNEL_STATE, SERVER_SUPPORT
        }

        private final Type type;
        private final String source;
        private final String target;
        private final String message;
        private final String additionalData;
        private final List<IrcEvent> historyMessages;

        public IrcEvent(Type type, String source, String target, String message,
                        String additionalData, List<IrcEvent> historyMessages) {
            this.type = type;
            this.source = source;
            this.target = target;
            this.message = message;
            this.additionalData = additionalData;
            this.historyMessages = historyMessages;
        }

        public IrcEvent(Type type, String source, String target, String message, String additionalData) {
            this(type, source, target, message, additionalData, null);
        }
    }
}
