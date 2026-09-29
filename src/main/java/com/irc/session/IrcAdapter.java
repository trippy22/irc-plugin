package com.irc.session;

import com.irc.protocol.ChannelListEntry;
import com.irc.IrcConfig;
import com.irc.model.IrcMessage;
import com.irc.ui.IrcPanel;
import com.irc.protocol.SimpleIrcClient;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adapter class to bridge between SimpleIrcClient
 */
@Slf4j
public class IrcAdapter {
    @Getter
    private SimpleIrcClient client;
    private volatile boolean active = true;
    private Consumer<IrcMessage> messageConsumer;
    private IrcConfig config;
    private volatile IrcPanel panel;
    private final java.util.Set<String> dirtyRosters = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean rosterRefreshQueued = new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * The query behind the in-flight LIST, replayed by the dialog's Refresh button. Written on
     * the caller's thread ({@link #requestChannelList}) and read on the IRC reader thread when
     * {@code CHANNEL_LIST} fires - volatile so that handoff doesn't depend on the incidental
     * happens-before edge created by both sides passing through the same monitor.
     */
    private volatile String lastChannelListQuery = "";
    private boolean listPending; // EDT-owned, includes time waiting in the output queue.
    private long listRequestId;
    // EDT-owned intent: late JOIN/NAMES/messages must not reopen an explicitly closed tab.
    private final java.util.Set<String> closedChannels = new java.util.HashSet<>();

    private boolean accepts(String channel) {
        return closedChannels.stream().noneMatch(closed -> client.sameName(closed, channel));
    }

    /**
     * Initialize the client with the provided config
     */
    public void initialize(IrcConfig config, Consumer<IrcMessage> messageConsumer, IrcPanel panel, String currentNick) {
        this.messageConsumer = messageConsumer;
        this.active = true;
        this.config = config;
        this.panel = panel;

        client = new SimpleIrcClient()
                .server(config.server().getHostname(), 6697, true)
                .credentials(currentNick, "runelite", currentNick);

        if (config.password() != null && !config.password().isEmpty()) {
            client.sasl(config.accountName(), config.password());
        }

        client.setRawLogging(config.logRawLines());

        setupEventHandlers();
    }

    /**
     * Connect to the IRC server
     */
    public void connect() {
        client.connect();
    }

    /**
     * Disconnect from the IRC server
     */
    public void disconnect(String reason) {
        client.disconnect(reason);
    }
    public void disconnect() {
        client.disconnect();
    }

    /**
     * Join a channel
     */
    public void joinChannel(String channel, String password) {
        onEdt(() -> {
            if (SimpleIrcClient.validChannel(channel)) {
                closedChannels.removeIf(closed -> client.sameName(closed, channel));
                if (panel != null) panel.addChannel(channel);
            }
            client.joinChannel(channel, password);
        });
    }

    /**
     * Leave a channel
     */
    public void leaveChannel(String channel) {
        leaveChannel(channel, null);
    }

    /**
     * Leave a channel with a reason
     */
    public void leaveChannel(String channel, String reason) {
        onEdt(() -> {
            closedChannels.add(channel);
            client.leaveChannel(channel, reason);
        });
    }

    /**
     * Send a message to a target (channel or user)
     */
    public void sendMessage(String target, String message) {
        client.sendMessage(target, message, () -> processMessage(new IrcMessage(
                target, getNick(), message, IrcMessage.MessageType.PRIVATE, Instant.now())));
    }

    public void sendAction(String target, String action) {
        client.sendAction(target, action, () -> processMessage(new IrcMessage(
                target, "* " + getNick(), action, IrcMessage.MessageType.PRIVATE, Instant.now())));
    }

    public void sendNotice(String target, String message) {
        client.sendNotice(target, message, () -> processMessage(new IrcMessage(
                "System", getNick(), "Notice to " + target + ": " + message,
                IrcMessage.MessageType.SYSTEM, Instant.now())));
    }
    /**
     * Change nickname
     */
    public void setNick(String nick) {
        client.setNick(nick);
    }

    /**
     * Send a raw IRC command
     */
    public void sendRawLine(String command) {
        client.sendCommand(command, null);
    }

    /**
     * Asks the server for its channel list. {@code query} is passed through verbatim so server
     * filters such as ">50" or "*quest*" work as the user typed them.
     */
    public void requestChannelList(String query) {
        if (listPending) return;
        String trimmed = query != null ? query.trim() : "";
        lastChannelListQuery = trimmed;
        // A run abandoned by an earlier request (server truncated its reply, no 323, connection
        // survives) must not have this request's rows appended onto its stale leftovers.
        client.resetChannelListRun();
        // No trailing space on a bare LIST: some servers read "LIST " as an empty filter.
        listPending = true;
        long requestId = ++listRequestId;
        boolean accepted = client.sendCommand(trimmed.isEmpty() ? "LIST" : "LIST " + trimmed,
                () -> onEdt(() -> {
                    // A fast reply may already have arrived before this EDT callback.
                    if (listPending && requestId == listRequestId && panel != null) panel.armChannelListTimeout();
                }));
        if (!accepted) listPending = false;
    }

    public void channelListTimedOut() { listPending = false; }

    /** Applies the raw-logging setting to a live connection, so it can be turned on mid-problem. */
    public void setRawLogging(boolean enabled) {
        if (client != null) {
            client.setRawLogging(enabled);
        }
    }

    /** Whether the server has accepted registration and commands can be sent. */
    public boolean isConnected() {
        return client.isRegistered();
    }

    /**
     * Drops the reference to the panel, so an in-flight reply cannot drive a UI that is being
     * torn down. Without it a 323 arriving during shutdown still pops the channel browser for a
     * plugin that no longer exists.
     */
    public void clearPanel() {
        active = false;
        // Panel access belongs to the EDT, including retirement during plugin shutdown.
        SwingUtilities.invokeLater(() -> panel = null);
    }

    /**
     * Get the current nickname
     */
    public String getNick() {
        return client.getNick();
    }

    /**
     * Process and forward incoming messages to the plugin
     */
    private void processMessage(IrcMessage message) {
        onEdt(() -> {
            if (accepts(message.getChannel()) && messageConsumer != null) messageConsumer.accept(message);
        });
    }

    private void onEdt(Runnable action) {
        if (!active) return;
        if (SwingUtilities.isEventDispatchThread()) {
            if (active) action.run();
        } else SwingUtilities.invokeLater(() -> { if (active) action.run(); });
    }
    /**
     * Set up event handlers for the SimpleIrcClient
     */
    private void setupEventHandlers() {
        client.addEventListener(event -> {
            if (event.getType() == SimpleIrcClient.IrcEvent.Type.USERS_CHANGED) {
                dirtyRosters.add(event.getTarget());
                if (rosterRefreshQueued.compareAndSet(false, true)) onEdt(() -> {
                    rosterRefreshQueued.set(false);
                    for (String channel : dirtyRosters.toArray(new String[0])) {
                        dirtyRosters.remove(channel);
                        if (panel != null && accepts(channel)) panel.setChannelUsers(channel, client.getChannelUsers(channel));
                    }
                });
                return;
            }
            // Capture reply data with the event; roster refreshes above deliberately coalesce to the latest state.
            final String eventNick = client.getNick();
            final List<ChannelListEntry> eventList = event.getType() == SimpleIrcClient.IrcEvent.Type.CHANNEL_LIST
                    ? client.getChannelListSnapshot() : null;
            final boolean eventTruncated = client.isChannelListTruncated();
            final String eventQuery = lastChannelListQuery;
            onEdt(() -> handleEvent(event, eventNick, eventList, eventTruncated, eventQuery));
        });
    }

    private void handleEvent(SimpleIrcClient.IrcEvent event, String eventNick,
                             List<ChannelListEntry> eventList,
                             boolean eventTruncated, String eventQuery) {
        String target = event.getTarget();
        String source = event.getSource();

        switch (event.getType()) {
            case CONNECT:
                processMessage(new IrcMessage("System", "System", "Connected to IRC server, registering...", IrcMessage.MessageType.SYSTEM, Instant.now()));
                break;

            case REGISTERED:
                processMessage(new IrcMessage("System", "System", "Registration complete - ready for commands", IrcMessage.MessageType.SYSTEM, Instant.now()));
                processMessage(new IrcMessage("System", "System", "Welcome to IRC! To chat in the current channel, use '" + config.prefix() + "' followed by your message in the game chatbox.", IrcMessage.MessageType.SYSTEM, Instant.now()));
                processMessage(new IrcMessage("System", "System", "For a list of commands, type '/help' in the side panel input box.", IrcMessage.MessageType.SYSTEM, Instant.now()));
                break;

            case SASL_SUCCESS:
                processMessage(new IrcMessage("System", "System", "Authenticated via SASL.", IrcMessage.MessageType.SYSTEM, Instant.now()));
                break;

            case SASL_FAILED:
                processMessage(new IrcMessage("System", "System", "SASL authentication failed: " + event.getMessage() + " (continuing unauthenticated).", IrcMessage.MessageType.SYSTEM, Instant.now()));
                break;

            case DISCONNECT:
                listPending = false;
                // The client records why the link went down; without it this line is
                // indistinguishable from the user's own /quit.
                String disconnectText = event.getMessage() != null && !event.getMessage().isEmpty()
                        ? "Disconnected from IRC (" + event.getMessage() + ")"
                        : "Disconnected from IRC";
                processMessage(new IrcMessage("System", "System", disconnectText, IrcMessage.MessageType.SYSTEM, Instant.now()));
                for (String channel : (event.getAdditionalData() == null || event.getAdditionalData().isEmpty() ? new String[0] : event.getAdditionalData().split(","))) {
                    processMessage(new IrcMessage(channel, "System", disconnectText, IrcMessage.MessageType.SYSTEM, Instant.now()));
                }
                // Any outcome must cancel a pending LIST timeout, not just success - otherwise
                // a disconnect while one is armed fires a spurious "no response" 30s later.
                if (panel != null) {
                    panel.cancelChannelListTimeout();
                }
                break;

            case MESSAGE:
                if (Objects.equals(target, source)) {
                    switch (config.filterPMs()) {
                        case Current:
                            source = "[PM] " + source;
                            target = panel != null ? panel.getCurrentChannel() : "System";
                            break;
                        case Status:
                            source = "[PM] " + source;
                            target = "System";
                            break;
                        case Private:
                            target = event.getSource();
                            break;
                    }
                }
                processMessage(new IrcMessage(target, source, event.getMessage(), IrcMessage.MessageType.CHAT, Instant.now()));
                break;

            case ACTION:
                processMessage(new IrcMessage(event.getTarget(), "* " + event.getSource(), event.getMessage(), IrcMessage.MessageType.CHAT, Instant.now()));
                break;

            case JOIN:
                if (!config.hideConnectionMessages()) {
                    processMessage(new IrcMessage(
                            event.getTarget(),
                            "*",
                            event.getSource() + " has joined.",
                            IrcMessage.MessageType.JOIN,
                            Instant.now()
                    ));
                }
                break;

            case PART:
                if (!config.hideConnectionMessages()) {
                    processMessage(new IrcMessage(event.getTarget(), event.getSource() + " parted", (event.getMessage() != null ? event.getMessage() : " "), IrcMessage.MessageType.PART, Instant.now()));
                }
                break;

            case QUIT:
                if (!config.hideConnectionMessages() && event.getAdditionalData() != null && !event.getAdditionalData().isEmpty()) {
                    String[] channels = event.getAdditionalData().split(",");
                    for (String channel : channels) {
                        processMessage(new IrcMessage(channel, event.getSource() + " quit", event.getMessage() != null ? event.getMessage() : " ", IrcMessage.MessageType.QUIT, Instant.now()));
                    }
                }
                break;

            case NICK_CHANGE:
                if (panel != null) panel.renameChannel(event.getSource(), event.getMessage());
                String oldNick = event.getSource();
                String newNick = event.getMessage();

                if (event.getAdditionalData() != null) {
                    String[] channels = event.getAdditionalData().split(",");
                    for (String channel : channels) {
                        if (channel != null && !channel.isEmpty()) {
                            processMessage(new IrcMessage(channel, oldNick + " is now known as", newNick, IrcMessage.MessageType.NICK_CHANGE, Instant.now()));
                        }
                    }
                }
                break;

            case KICK:
                if (!config.hideConnectionMessages()) {
                    String[] kickParts = event.getMessage().split(" ", 2);
                    String kickedUser = kickParts[0];
                    String kickReason = kickParts.length > 1 ? kickParts[1] : "";
                    processMessage(new IrcMessage(event.getTarget(), event.getSource() + " kicked " + kickedUser, kickReason, IrcMessage.MessageType.KICK, Instant.now()));
                }
                break;

            case SERVER_NOTICE:
            case NOTICE:
                if (source != null && source.endsWith(".SwiftIRC.net")) {
                    if (!config.filterServerNotices()) {
                        target = "System";
                    } else {
                        target = source;
                    }
                } else {
                    source = "[N] " + source;
                    switch (config.filterNotices()) {
                        case Current:
                            target = panel != null ? panel.getCurrentChannel() : "System";
                            break;
                        case Status:
                            target = "System";
                            break;
                        case Private:
                            target = source;
                            break;
                    }
                }
                processMessage(new IrcMessage(target, source, event.getMessage(), IrcMessage.MessageType.NOTICE, Instant.now()));
                break;

            case CHANNEL_MODE:
                processMessage(new IrcMessage(event.getTarget(), event.getSource(), event.getMessage(), IrcMessage.MessageType.MODE, Instant.now()));
                break;

            case USER_MODE:
                processMessage(new IrcMessage("System", eventNick, event.getMessage(), IrcMessage.MessageType.MODE, Instant.now()));
                break;

            case TOPIC:
                processMessage(new IrcMessage(event.getTarget(), "* Topic", event.getMessage(), IrcMessage.MessageType.TOPIC, Instant.now()));
                break;

            case NAMES:
                processMessage(new IrcMessage(event.getTarget(), "Users", event.getMessage(), IrcMessage.MessageType.JOIN, Instant.now()));
                break;

            case CHANNEL_LIST:
                listPending = false;
                if (panel != null) {
                    // Snapshot on the IRC thread; it is immutable, so the EDT can hold it.
                    List<ChannelListEntry> channelList = eventList;
                    boolean truncated = eventTruncated;
                    String listQuery = eventQuery;
                    panel.showChannelList(channelList, listQuery, truncated);
                }
                break;

            case CHANNEL_LIST_FAILED:
                listPending = false;
                processMessage(new IrcMessage("System", "System",
                        "Channel list unavailable: " + event.getMessage(),
                        IrcMessage.MessageType.SYSTEM, Instant.now()));
                if (panel != null) {
                    panel.cancelChannelListTimeout();
                }
                break;

            case BAD_CHANNEL_KEY:
                String badChannel = event.getTarget();
                processMessage(new IrcMessage("System", "System", "Cannot join " + badChannel + ": " + event.getMessage(), IrcMessage.MessageType.SYSTEM, Instant.now()));
                if (panel != null && client.getDesiredChannels().keySet().stream()
                        .anyMatch(channel -> client.sameName(channel, badChannel))) {
                    String password = JOptionPane.showInputDialog(panel.getChatContent(),
                            "Enter password for " + badChannel + ":", "Channel Key Required", JOptionPane.QUESTION_MESSAGE);
                    // Modal dialogs run a nested event loop; the connection may have retired.
                    if (active && password != null && !password.isEmpty()
                            && client.getDesiredChannels().keySet().stream().anyMatch(c -> client.sameName(c, badChannel)))
                        joinChannel(badChannel, password);
                }
                break;
            case WHOIS_REPLY:
                processMessage(new IrcMessage("System", "WHOIS", event.getMessage(), IrcMessage.MessageType.SYSTEM, Instant.now()));
                break;

            case SERVER_ERROR:
                // Reported, but never disconnects: most error numerics (no such nick, not a
                // channel operator) leave a perfectly healthy connection in place.
                processMessage(new IrcMessage("System", "Error", event.getMessage(), IrcMessage.MessageType.SYSTEM, Instant.now()));
                break;

            case ERROR:
                processMessage(new IrcMessage("System", "Error", event.getMessage() != null ? event.getMessage() : "Unknown error", IrcMessage.MessageType.SYSTEM, Instant.now()));
                disconnect();
                break;

            case TOPIC_INFO:
                processMessage(new IrcMessage(event.getTarget(), event.getSource(), event.getMessage(), IrcMessage.MessageType.TOPIC, Instant.now()));
                break;

            case HISTORY_BATCH:
                if (event.getHistoryMessages() != null && !event.getHistoryMessages().isEmpty()) {
                    for (SimpleIrcClient.IrcEvent accEvent : event.getHistoryMessages()) {
                        Instant timestamp;
                        try {
                            String timeStr = accEvent.getAdditionalData();
                            timestamp = (timeStr != null && !timeStr.isEmpty())
                                ? Instant.parse(timeStr)
                                : Instant.now();
                        } catch (Exception e) {
                            timestamp = Instant.now();
                        }
                        String sender = accEvent.getSource() != null ? accEvent.getSource() : "";
                        if (accEvent.getType() == SimpleIrcClient.IrcEvent.Type.ACTION) {
                            sender = "* " + sender;
                        }
                        processMessage(new IrcMessage(
                            event.getTarget(), sender, accEvent.getMessage(),
                            IrcMessage.MessageType.HISTORY, timestamp
                        ));
                    }
                    processMessage(new IrcMessage(
                        event.getTarget(), "*", "--- Begin of chat ---",
                        IrcMessage.MessageType.HISTORY_SEPARATOR, Instant.now()
                    ));
                }
                break;
        }
    }
}
