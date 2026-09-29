package com.irc.protocol;

/** Shared IRC identity rules for the protocol model and all views. */
public final class IrcNames {
    private IrcNames() { }
    public static String fold(String name, String mapping) {
        StringBuilder result = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (c >= 'A' && c <= 'Z') c += 32;
            if (!"ascii".equals(mapping)) {
                if (c == '[') c = '{';
                else if (c == ']') c = '}';
                else if (c == '\\') c = '|';
                else if (c == '^' && "rfc1459".equals(mapping)) c = '~';
            }
            result.append(c);
        }
        return result.toString();
    }
}
