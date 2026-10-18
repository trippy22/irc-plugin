package com.irc.scripting;

import java.util.Objects;

/** Immutable IRC text only. No plugin, client, UI, or connection objects cross this boundary. */
public final class ScriptContext {
    public final String network, channel, nick, me, text;
    public ScriptContext(String network, String channel, String nick, String me, String text) {
        this.network = Objects.requireNonNull(network);
        this.channel = Objects.requireNonNull(channel);
        this.nick = Objects.requireNonNull(nick);
        this.me = Objects.requireNonNull(me);
        this.text = Objects.requireNonNull(text);
    }
    public boolean isChannel() { return channel.startsWith("#") || channel.startsWith("&"); }
}
