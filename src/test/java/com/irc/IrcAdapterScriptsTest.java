package com.irc;

import com.irc.scripting.ScriptContext;

import org.junit.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class IrcAdapterScriptsTest {
    @Test public void forwardsOriginalLiveTextButNotHistoryActionsOrSelfEchoes() {
        List<ScriptContext> events = new ArrayList<>();
        IrcAdapter adapter = new IrcAdapter();
        adapter.initialize(new IrcConfig() {
            @Override public String username() { return "me"; }
            @Override public String password() { return ""; }
        }, m -> {}, null, "me");
        adapter.setScriptConsumer(events::add);
        adapter.getClient().processLine(":server 001 me :Welcome");
        adapter.getClient().processLine(":Alice!u@h PRIVMSG #test :!ping");
        adapter.getClient().processLine(":Bob!u@h PRIVMSG me :private");
        adapter.getClient().processLine(":me!u@h PRIVMSG #test :!ping");
        adapter.getClient().processLine(":Alice!u@h PRIVMSG #test :\1ACTION waves\1");
        adapter.getClient().processLine("@time=" + Instant.now().minusSeconds(3600) + " :Alice!u@h PRIVMSG #test :!ping");
        assertEquals(2, events.size());
        assertEquals("Alice", events.get(0).nick); assertEquals("#test", events.get(0).channel);
        assertEquals("Bob", events.get(1).nick); assertEquals("Bob", events.get(1).channel);
        assertEquals(NetworkConfig.SWIFTIRC_ID, events.get(0).network);
        adapter.detach(); adapter.getClient().processLine(":Alice!u@h PRIVMSG #test :!ping");
        assertEquals(2, events.size());
    }
}
