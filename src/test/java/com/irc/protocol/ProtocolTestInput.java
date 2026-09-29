package com.irc.protocol;

/** Test-only input seam for session tests; raw parsing stays package-private in production. */
public final class ProtocolTestInput {
    private ProtocolTestInput() { }

    public static void receive(SimpleIrcClient client, String line) {
        client.processLine(line);
    }
}
