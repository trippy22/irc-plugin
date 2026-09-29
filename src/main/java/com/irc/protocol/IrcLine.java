package com.irc.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;

/** Pure wire decoding: no session changes, callbacks, or Swing dependencies. */
final class IrcLine {
    private static final int MAX_INCOMING_CHARS = 8192;
    final String source, command;
    final Map<String, String> tags;
    final List<String> params;

    private IrcLine(String source, String command, Map<String, String> tags, List<String> params) {
        this.source = source; this.command = command;
        this.tags = Collections.unmodifiableMap(tags);
        this.params = Collections.unmodifiableList(params);
    }

    static String read(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') line.setLength(line.length() - 1);
                return line.toString();
            }
            if (line.length() >= MAX_INCOMING_CHARS) throw new IOException("Incoming IRC line exceeds the receive limit");
            line.append((char) c);
        }
        return line.length() == 0 ? null : line.toString();
    }

    static IrcLine parse(String line) {
        if (line == null || line.isEmpty() || line.length() > MAX_INCOMING_CHARS
                || line.indexOf('\0') >= 0 || line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0) return null;
        Map<String, String> tags = new HashMap<>();
        int position = 0;
        if (line.charAt(0) == '@') {
            int end = line.indexOf(' ');
            if (end < 0) return null;
            for (String tag : line.substring(1, end).split(";")) {
                int eq = tag.indexOf('=');
                tags.put(eq < 0 ? tag : tag.substring(0, eq), eq < 0 ? "" : unescape(tag.substring(eq + 1)));
            }
            position = end + 1;
        }
        while (position < line.length() && line.charAt(position) == ' ') position++;
        String source = "";
        if (position < line.length() && line.charAt(position) == ':') {
            int end = line.indexOf(' ', position);
            if (end < 0) return null;
            source = line.substring(position + 1, end);
            position = end + 1;
        }
        while (position < line.length() && line.charAt(position) == ' ') position++;
        int end = line.indexOf(' ', position);
        if (end < 0) end = line.length();
        String command = line.substring(position, end).toUpperCase(Locale.ROOT);
        if (!command.matches("[A-Z]+|[0-9]{3}")) return null;
        position = end;
        List<String> params = new ArrayList<>();
        while (position < line.length()) {
            if (line.charAt(position) == ' ') { position++; continue; }
            if (line.charAt(position) == ':') { params.add(line.substring(position + 1)); break; }
            end = line.indexOf(' ', position);
            if (end < 0) end = line.length();
            params.add(line.substring(position, end));
            position = end;
        }
        return new IrcLine(source, command, tags, params);
    }

    private static String unescape(String value) {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                if (++i == value.length()) break;
                c = value.charAt(i);
                switch (c) {
                    case ':': c = ';'; break;
                    case 's': c = ' '; break;
                    case 'r': c = '\r'; break;
                    case 'n': c = '\n'; break;
                    default: break;
                }
            }
            decoded.append(c);
        }
        return decoded.toString();
    }
}
