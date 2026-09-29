package com.irc;

import javax.swing.SwingUtilities;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Application-level session owner. Commands and delivered events are serialized on the EDT;
 * blocking socket work stays in SimpleIrcClient. Neither window host owns connection state.
 */
final class IrcSessionController {
    private final Supplier<IrcAdapter> adapters;
    private IrcAdapter adapter;
    private IrcConfig config;
    private IrcPanel panel;
    private IrcChatModel model;
    private Consumer<IrcMessage> messages;
    private String configuredNick;
    private long generation;
    private boolean retired;
    private boolean reloading;

    IrcSessionController() { this(IrcAdapter::new); }
    IrcSessionController(Supplier<IrcAdapter> adapters) { this.adapters = adapters; }

    void initialize(IrcConfig config, Consumer<IrcMessage> messages, IrcPanel panel, String nick) {
        this.config = config; this.messages = messages; this.panel = panel; this.model = panel.getModel();
        create(nick);
    }
    private void create(String nick) {
        configuredNick = config.username();
        long session = ++generation;
        adapter = adapters.get();
        IrcAdapter current = adapter;
        current.initialize(config, message -> {
            if (isCurrent(session) && model.accepts(message.getChannel())) messages.accept(message);
        }, panel, nick);
        current.observe((event, acceptedNick) -> {
            if (!isCurrent(session)) return;
            model.setCaseMapping(current.getClient().getCaseMapping());
            switch (event.getType()) {
                case CONNECT: model.connection(IrcChatModel.Connection.REGISTERING, null); break;
                case REGISTERED: model.connection(IrcChatModel.Connection.READY, acceptedNick); break;
                case DISCONNECT: model.connection(IrcChatModel.Connection.OFFLINE, null); break;
                case NICK_CHANGE:
                    model.connection(model.snapshot().connection, acceptedNick);
                    model.rename(event.getSource(), event.getMessage());
                    break;
                case CHANNEL_STATE:
                    model.membership(event.getTarget(), IrcChatModel.Membership.valueOf(event.getMessage()), event.getAdditionalData());
                    break;
                default: break;
            }
        }, this::joinChannel);
    }
    private boolean isCurrent(long session) { return !retired && session == generation; }
    void connect() {
        model.connection(IrcChatModel.Connection.CONNECTING, null);
        adapter.connect();
    }
    void reload() {
        if (retired || reloading) return;
        reloading = true;
        IrcAdapter old = adapter;
        long closingGeneration = ++generation; // One gate for every callback from the retired session.
        old.clearPanel();
        model.connection(IrcChatModel.Connection.DISCONNECTING, null);
        panel.cancelChannelListTimeout();
        old.disconnect("Reloading, brb");
        old.getClient().onDisconnected(() -> SwingUtilities.invokeLater(() -> {
            if (retired || generation != closingGeneration) return;
            Map<String, String> channels = old.getClient().getDesiredChannels();
            String nick = Objects.equals(configuredNick, config.username()) ? old.getClient().getConfirmedNick() : null;
            if (nick == null) nick = IrcPlugin.sanitizeNick(config.username());
            if (nick.isEmpty()) nick = "RLGuest" + (int) (Math.random() * 9999 + 1);
            create(nick);
            // Restore wire intent without treating existing conversations as newly opened tabs.
            channels.forEach(adapter::joinChannel);
            reloading = false;
            connect();
        }));
    }
    void joinChannel(String channel, String key) {
        if (retired) return;
        if (!SimpleIrcClient.validChannel(channel)) { adapter.joinChannel(channel, key); return; }
        model.open(channel, config.autofocusOnNewTab() || model.snapshot().conversations.size() == 1);
        model.membership(channel, isConnected() ? IrcChatModel.Membership.JOINING : IrcChatModel.Membership.WAITING, "");
        adapter.joinChannel(channel, key);
    }
    void leaveChannel(String channel) { leaveChannel(channel, null); }
    void leaveChannel(String channel, String reason) {
        model.close(channel);
        adapter.leaveChannel(channel, reason);
    }
    void disconnect(String reason) {
        if (!retired) model.connection(IrcChatModel.Connection.DISCONNECTING, null);
        adapter.disconnect(reason);
    }
    void clearPanel() {
        retired = true; generation++;
        adapter.clearPanel();
    }
    SimpleIrcClient getClient() { return adapter.getClient(); }
    String getNick() { return adapter.getNick(); }
    boolean isConnected() { return !reloading && adapter.isConnected(); }
    private void submit(Runnable command) {
        if (retired) return;
        if (reloading) messages.accept(new IrcMessage("System", "System", "Reconnecting; command was not sent.",
                IrcMessage.MessageType.SYSTEM, java.time.Instant.now()));
        else command.run();
    }
    void sendMessage(String target, String text) { submit(() -> adapter.sendMessage(target, text)); }
    void sendAction(String target, String text) { submit(() -> adapter.sendAction(target, text)); }
    void sendNotice(String target, String text) { submit(() -> adapter.sendNotice(target, text)); }
    void setNick(String nick) { submit(() -> adapter.setNick(nick)); }
    void sendRawLine(String text) { submit(() -> adapter.sendRawLine(text)); }
    void identify(String text) { submit(() -> adapter.getClient().sendMessage("NickServ", text)); }
    void requestChannelList(String query) { adapter.requestChannelList(query); }
    void channelListTimedOut() { adapter.channelListTimedOut(); }
    void setRawLogging(boolean enabled) { adapter.setRawLogging(enabled); }
}
