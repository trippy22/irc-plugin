package com.irc.model;

import com.irc.protocol.ChannelUserList;
import com.irc.protocol.IrcNames;
import com.irc.protocol.SimpleIrcClient;

import javax.swing.SwingUtilities;
import java.util.*;
import java.util.function.Consumer;

/** Shared conversation state. Mutations belong to the EDT; snapshots are safe on any thread. */
public final class IrcChatModel {
    public enum Connection { OFFLINE, CONNECTING, REGISTERING, READY, DISCONNECTING }
    public enum Membership { NONE, WAITING, JOINING, JOINED, LEAVING, FAILED, KICKED, OFFLINE }

    public static final class Conversation {
        public final long id;
        public final String name, draft, detail;
        public final boolean unread;
        public final Membership membership;
        public final List<IrcMessage> messages;
        public final List<ChannelUserList.Entry> users;
        private Conversation(Buffer b) {
            id = b.id; name = b.name; draft = b.draft; detail = b.detail;
            unread = b.unread; membership = b.membership;
            messages = b.historySnapshot();
            users = b.users;
        }
    }

    public static final class Snapshot {
        public final Connection connection;
        public final String nick, selected;
        public final List<Conversation> conversations;
        Snapshot(Connection connection, String nick, String selected, Collection<Buffer> buffers) {
            this.connection = connection; this.nick = nick; this.selected = selected;
            List<Conversation> copy = new ArrayList<>();
            buffers.forEach(b -> copy.add(new Conversation(b)));
            conversations = Collections.unmodifiableList(copy);
        }
    }

    private static final class Buffer {
        final long id;
        String name, draft = "", detail = "";
        boolean unread;
        Membership membership = Membership.NONE;
        final List<IrcMessage> messages = new ArrayList<>();
        long historyVersion, publishedVersion = -1;
        List<IrcMessage> publishedMessages = Collections.emptyList();
        List<ChannelUserList.Entry> users = Collections.emptyList();
        Buffer(long id, String name) { this.id = id; this.name = name; }
        List<IrcMessage> historySnapshot() {
            if (publishedVersion != historyVersion) {
                publishedMessages = Collections.unmodifiableList(new ArrayList<>(messages));
                publishedVersion = historyVersion;
            }
            return publishedMessages;
        }
    }

    private final List<Buffer> buffers = new ArrayList<>();
    private final Set<String> closedChannels = new HashSet<>();
    private final List<Consumer<Snapshot>> listeners = new ArrayList<>();
    private String selected = "System", nick = "", caseMapping = "rfc1459";
    private Connection connection = Connection.OFFLINE;
    private long nextId;
    private int historyLimit = 500;
    private volatile Snapshot snapshot;

    public IrcChatModel() {
        buffers.add(new Buffer(++nextId, "System"));
        snapshot = new Snapshot(connection, nick, selected, buffers);
    }

    public Snapshot snapshot() { return snapshot; }
    private static void requireOwner() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("IRC model mutations require EDT");
    }
    public void listen(Consumer<Snapshot> listener) { requireOwner(); listeners.add(listener); listener.accept(snapshot); }
    public void unlisten(Consumer<Snapshot> listener) { requireOwner(); listeners.remove(listener); }
    private void publish() {
        snapshot = new Snapshot(connection, nick, selected, buffers);
        for (Consumer<Snapshot> listener : new ArrayList<>(listeners)) listener.accept(snapshot);
    }
    public boolean sameName(String a, String b) { return a != null && b != null && fold(a).equals(fold(b)); }
    private String fold(String value) { return IrcNames.fold(value, caseMapping); }
    private Buffer find(String name) {
        for (Buffer b : buffers) if (sameName(b.name, name)) return b;
        return null;
    }
    public void setHistoryLimit(int limit) { requireOwner(); historyLimit = Math.max(1, limit); }
    public void setCaseMapping(String mapping) {
        requireOwner();
        if (caseMapping.equals(mapping)) return;
        caseMapping = mapping;
        // Resolve newly equivalent names through the same merge used for nickname changes.
        for (Buffer b : new ArrayList<>(buffers)) {
            Buffer first = find(b.name);
            if (first != b) merge(b, first);
        }
        publish();
    }
    public void connection(Connection state, String acceptedNick) {
        requireOwner(); connection = state;
        if (acceptedNick != null) nick = acceptedNick;
        if (state != Connection.READY) for (Buffer b : buffers) {
            b.users = Collections.emptyList();
            if (SimpleIrcClient.isChannel(b.name)) b.membership =
                    state == Connection.OFFLINE ? Membership.OFFLINE : Membership.WAITING;
        }
        publish();
    }
    public void open(String name, boolean focus) {
        requireOwner();
        if (name == null || name.isEmpty()) return;
        closedChannels.removeIf(n -> sameName(n, name));
        Buffer b = find(name);
        if (b == null) { b = new Buffer(++nextId, name); buffers.add(b); }
        if (focus) { selected = b.name; b.unread = false; }
        publish();
    }
    public void close(String name) {
        requireOwner();
        if (sameName(name, "System")) return;
        if (SimpleIrcClient.isChannel(name)) closedChannels.add(name);
        Buffer b = find(name);
        buffers.remove(b);
        if (sameName(selected, name)) selected = "System";
        publish();
    }
    public boolean accepts(String name) {
        return name != null && closedChannels.stream().noneMatch(n -> sameName(n, name));
    }
    public void select(String name) {
        requireOwner(); Buffer b = find(name);
        if (b == null) return;
        selected = b.name; b.unread = false; publish();
    }
    public void draft(String name, String value) {
        requireOwner(); Buffer b = find(name);
        if (b == null || b.draft.equals(value)) return;
        b.draft = value; publish();
    }
    public void append(IrcMessage message, boolean focusNew) {
        requireOwner();
        if (!accepts(message.getChannel())) return;
        Buffer b = find(message.getChannel());
        if (b == null) {
            b = new Buffer(++nextId, message.getChannel()); buffers.add(b);
            if (focusNew) selected = b.name;
        }
        b.messages.add(message);
        b.historyVersion++;
        trim(b);
        b.unread = !sameName(selected, b.name);
        publish();
    }
    public void clear(String name) { requireOwner(); Buffer b = find(name); if (b != null) { b.messages.clear(); b.historyVersion++; publish(); } }
    public void users(String name, List<ChannelUserList.Entry> users) {
        requireOwner(); Buffer b = find(name);
        if (b == null || b.users.equals(users)) return;
        if (b.membership != Membership.NONE && b.membership != Membership.JOINED) return;
        b.users = Collections.unmodifiableList(new ArrayList<>(users)); publish();
    }
    public void membership(String name, Membership state, String detail) {
        requireOwner(); Buffer b = find(name);
        if (b == null) return;
        b.membership = state; b.detail = detail == null ? "" : detail;
        if (state != Membership.JOINED) b.users = Collections.emptyList();
        publish();
    }
    public void rename(String oldName, String newName) {
        requireOwner(); Buffer source = find(oldName);
        if (source == null || sameName(source.name, "System")) return;
        Buffer target = find(newName);
        if (target != null && target != source) merge(source, target);
        else {
            if (sameName(selected, oldName)) selected = newName;
            source.name = newName;
        }
        publish();
    }
    private void merge(Buffer source, Buffer target) {
        target.messages.addAll(source.messages);
        target.historyVersion++;
        target.messages.sort(Comparator.comparing(IrcMessage::getTimestamp));
        trim(target);
        // Preserve both unsent drafts when two conversations converge.
        if (!source.draft.isEmpty()) target.draft = target.draft.isEmpty() ? source.draft : target.draft + " " + source.draft;
        target.unread |= source.unread;
        if (sameName(selected, source.name)) selected = target.name;
        buffers.remove(source);
    }
    private void trim(Buffer b) {
        if (b.messages.size() > historyLimit) b.messages.subList(0, b.messages.size() - historyLimit).clear();
    }
}
