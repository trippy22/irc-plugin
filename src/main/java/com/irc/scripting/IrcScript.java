package com.irc.scripting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A deliberately small, interpreted IRC language. No Java evaluation or host object access. */
final class IrcScript {
    static final int MAX_SOURCE = 65536;
    static final int MAX_VALUE = 2048;
    static final int MAX_VARIABLES = 128;
    private static final Pattern NAME = Pattern.compile("[a-zA-Z_][a-zA-Z_0-9]*");
    private static final Set<String> COMMANDS = new HashSet<>(Arrays.asList(
            "echo", "msg", "notice", "var", "set", "unset", "inc", "dec", "return", "halt", "timer"));
    private static final Set<String> FUNCTIONS = new HashSet<>(Arrays.asList(
            "upper", "lower", "len", "calc", "gettok", "left", "right", "replace", "replacecs", "chr"));
    private final Map<String, List<Statement>> aliases = new LinkedHashMap<>();
    private final Set<String> localAliases = new HashSet<>();
    private final List<Handler> handlers = new ArrayList<>();

    interface Host {
        void echo(String text);
        void send(String target, String text, boolean notice);
        void timer(String name, int repetitions, int seconds, Runnable action, ScriptContext context);
        void cancelTimer(String name);
    }


    static final class ScriptException extends IllegalArgumentException {
        final int line;
        ScriptException(int line, String message) {
            super((line > 0 ? "Line " + line + ": " : "") + message);
            this.line = line;
        }
    }

    private static ScriptException error(int line, String text) { return new ScriptException(line, text); }
    private static String key(String s) { return s.toLowerCase(Locale.ROOT); }
    private static String bounded(String s) {
        if (s.length() > MAX_VALUE) throw error(0, "Value exceeds " + MAX_VALUE + " characters");
        return s;
    }

    static IrcScript compile(String source) {
        if (source.length() > MAX_SOURCE) throw error(0, "Script is too large (64 KiB character limit)");
        IrcScript result = new IrcScript();
        Parser parser = new Parser(source);
        List<Statement> declarations = parser.block(false, 0);
        if (declarations.size() > 128) throw error(0, "At most 128 declarations per script");
        for (Statement declaration : declarations) {
            String head = declaration.text;
            if (head.regionMatches(true, 0, "alias ", 0, 6)) {
                String definition = head.substring(6).trim();
                boolean local = definition.startsWith("-l ");
                if (local) definition = definition.substring(3).trim();
                String[] definitionParts = definition.split("\\s+", 2);
                String name = key(definitionParts[0]);
                if (declaration.body == null && definitionParts.length == 2)
                    declaration.body = new ArrayList<>(Collections.singletonList(new Statement(definitionParts[1], declaration.line)));
                else if (definitionParts.length > 1) throw error(declaration.line, "Invalid alias declaration");
                if (!NAME.matcher(name).matches() || COMMANDS.contains(name) || FUNCTIONS.contains(name)
                        || name.startsWith("timer") || Arrays.asList("if", "elseif", "else", "nick", "chan", "me", "network", "null", "true", "false", "result", "v1", "v2").contains(name))
                    throw error(declaration.line, "Invalid or reserved alias name");
                if (declaration.body == null) throw error(declaration.line, "Alias requires { ... }");
                if (result.aliases.putIfAbsent(name, declaration.body) != null)
                    throw error(declaration.line, "Duplicate alias: " + name);
                if (local) result.localAliases.add(name);
            } else if (head.regionMatches(true, 0, "on *:TEXT:", 0, 10) || head.regionMatches(true, 0, "on 1:TEXT:", 0, 10)) {
                String spec = head.substring(10);
                if (!spec.endsWith(":")) throw error(declaration.line, "Expected on *:TEXT:pattern:target: { ... }");
                int colon = spec.lastIndexOf(':', spec.length() - 2);
                if (colon <= 0 || declaration.body == null) throw error(declaration.line, "Invalid TEXT event");
                String target = spec.substring(colon + 1, spec.length() - 1);
                if (target.isEmpty()) throw error(declaration.line, "Missing TEXT target");
                if (target.length() > 100 || colon > 256) throw error(declaration.line, "Event pattern or target is too long");
                result.handlers.add(new Handler(spec.substring(0, colon), target, declaration.body));
            } else throw error(declaration.line, "Only alias and on *:TEXT declarations are supported");
        }
        for (List<Statement> body : result.aliases.values()) result.validate(body);
        for (Handler handler : result.handlers) result.validate(handler.body);
        return result;
    }

    Set<String> aliasNames() {
        Set<String> names = new HashSet<>(aliases.keySet()); names.removeAll(localAliases); return Collections.unmodifiableSet(names);
    }

    private void validate(List<Statement> statements) {
        validate(statements, 0);
    }
    private void validate(List<Statement> statements, int depth) {
        if (depth > 16) throw error(0, "Statement nesting limit exceeded");
        for (int i = 0; i < statements.size(); i++) {
            Statement s = statements.get(i);
            String lower = key(s.text);
            if (lower.startsWith("if ") || lower.startsWith("if(") || lower.startsWith("elseif ") || lower.startsWith("elseif(")) {
                boolean alternative = lower.startsWith("elseif");
                if (alternative && (i == 0 || statements.get(i - 1).condition == null)) throw error(s.line, "elseif must follow if or elseif");
                s.command = alternative ? "elseif" : "if";
                String condition = s.text.substring(alternative ? 6 : 2).trim();
                int close = matchingParen(condition, 0, s.line);
                String inline = condition.substring(close + 1).trim();
                if (s.body == null && !inline.isEmpty()) s.body = new ArrayList<>(Collections.singletonList(new Statement(inline, s.line)));
                else if (!inline.isEmpty()) throw error(s.line, "Unexpected text after condition");
                if (s.body == null) throw error(s.line, "Missing if body");
                s.condition = condition(condition.substring(1, close), s.line, 0);
                validate(s.body, depth + 1);
            } else if (lower.equals("else") || lower.startsWith("else ")) {
                if (s.body == null && lower.length() > 5) s.body = new ArrayList<>(Collections.singletonList(new Statement(s.text.substring(5), s.line)));
                if (i == 0 || statements.get(i - 1).condition == null || s.body == null)
                    throw error(s.line, "else must follow if and have a braced body");
                s.command = "else";
                validate(s.body, depth + 1);
            } else {
                if (s.body != null) throw error(s.line, "Unexpected command block");
                String[] parts = s.text.split("\\s+", 2);
                s.command = key(parts[0].replaceFirst("^[/.]", ""));
                if (s.command.startsWith("timer")) {
                    s.timerName = s.command.substring(5);
                    if (!s.timerName.matches("[a-zA-Z_0-9-]{0,40}")) throw error(s.line, "Invalid timer name");
                    s.command = "timer";
                }
                boolean assignment = s.command.startsWith("%");
                if (assignment) s.command = "set";
                if (!COMMANDS.contains(s.command) && !aliases.containsKey(s.command))
                    throw error(s.line, "Unsupported command: " + s.command);
                String args = parts.length == 2 ? parts[1] : "";
                if (assignment) args = s.text;
                if (s.command.equals("var") || s.command.equals("set")) {
                    if (args.startsWith("-")) {
                        String[] flags = args.split("\\s+", 2);
                        if (!flags[0].matches("-[ngl]+") || flags.length < 2 || (flags[0].contains("g") && flags[0].contains("l")))
                            throw error(s.line, "Supported variable switches: -n, -g, -l");
                        s.flags = flags[0]; args = flags[1];
                    }
                    Matcher m = Pattern.compile("^%([a-zA-Z_][a-zA-Z_0-9.]*)" + (s.command.equals("var") ? "(?:\\s*=\\s*(.*))?$" : "(?:\\s+(.*))?$")).matcher(args);
                    if (!m.matches()) throw error(s.line, "Expected " + s.command + (s.command.equals("var") ? " %name = value" : " %name value"));
                    s.variable = key(m.group(1)); args = m.group(2) == null ? "" : m.group(2);
                    // Keep the previous subset's optional '=' spelling as a migration convenience.
                    if (s.command.equals("set") && (args.equals("=") || args.startsWith("= "))) args = args.substring(1).trim();
                } else if (s.command.equals("inc") || s.command.equals("dec")) {
                    Matcher m = Pattern.compile("^%([a-zA-Z_][a-zA-Z_0-9.]*)(?:\\s+(.*))?$").matcher(args);
                    if (!m.matches()) throw error(s.line, "Expected inc/dec %name [amount]");
                    s.variable = key(m.group(1)); args = m.group(2) == null ? "1" : m.group(2);
                } else if (s.command.equals("unset")) {
                    if (!args.matches("%[a-zA-Z_0-9.*?]+(?:\\s+%[a-zA-Z_0-9.*?]+)*")) throw error(s.line, "Expected unset %name [more names]");
                    s.variable = key(args); args = "";
                } else if (s.command.equals("timer")) {
                    if (args.equalsIgnoreCase("off")) { s.flags = "off"; }
                    else {
                        String[] timer = args.split("\\s+", 3);
                        if (timer.length != 3 || timer[0].startsWith("-")) throw error(s.line, "Expected timer[name] repetitions seconds command; timer switches are disabled");
                        s.delayed = new Statement(timer[2], s.line);
                        String delayedCommand = key(timer[2].split("\\s+", 2)[0].replaceFirst("^[/.]", ""));
                        if (!Arrays.asList("echo", "msg", "notice").contains(delayedCommand) && !aliases.containsKey(delayedCommand))
                            throw error(s.line, "Unsupported timer command: " + delayedCommand);
                        validate(Collections.singletonList(s.delayed), depth + 1);
                        if (!Arrays.asList("echo", "msg", "notice").contains(s.delayed.command) && !aliases.containsKey(s.delayed.command))
                            throw error(s.line, "Timers may execute only echo, msg, notice or a declared alias");
                        args = timer[0] + " " + timer[1];
                    }
                }
                s.value = template(args, s.line, 0);
            }
        }
    }

    String run(String alias, String args, ScriptContext context, Map<String, String> globals, Host host) {
        try { return call(key(alias), args, new Frame(context, globals, host, new Budget()), 0); }
        catch (Halt ignored) { return ""; }
    }

    void text(ScriptContext context, Map<String, String> globals, Host host) {
        Frame frame = new Frame(context, globals, host, new Budget());
        for (Handler h : handlers) {
            frame.budget.step();
            boolean channel = context.channel.startsWith("#") || context.channel.startsWith("&");
            boolean target = h.target.equals("*") || (h.target.equals("#") ? channel
                    : h.target.equals("?") ? !channel : glob(h.target, context.channel));
            if (target && glob(h.pattern, context.text)) {
                Frame event = frame.child(context.text, 0);
                try { execute(h.body, event); } catch (Halt ignored) { }
                return; // mIRC uses the first matching handler in each script file.
            }
        }
    }

    private String call(String alias, String args, Frame parent, int depth) {
        List<Statement> body = aliases.get(alias);
        if (body == null) throw error(0, "Unknown alias: " + alias);
        if (depth > 16) throw error(0, "Alias nesting limit exceeded");
        if (parent.calls.contains(alias)) throw error(0, "Recursive alias call is not permitted: " + alias);
        Frame frame = parent.child(args, depth);
        frame.calls.add(alias);
        try { execute(body, frame); } finally { frame.calls.remove(alias); }
        return frame.result;
    }

    private void execute(List<Statement> body, Frame f) {
        for (int i = 0; i < body.size() && !f.returned; i++) {
            Statement s = body.get(i);
            f.budget.step();
            try {
                if (s.condition != null) {
                    boolean yes = s.condition.test(f);
                    if (yes) execute(s.body, f);
                    while (i + 1 < body.size() && (body.get(i + 1).command.equals("elseif") || body.get(i + 1).command.equals("else"))) {
                        Statement alternative = body.get(++i);
                        if (!yes && (alternative.condition == null || alternative.condition.test(f))) { execute(alternative.body, f); yes = true; }
                    }
                    continue;
                }
                String value = s.value.eval(f);
                switch (s.command) {
                    case "var": case "set": {
                        Map<String, String> destination = s.flags.contains("g") ? f.globals
                                : s.flags.contains("l") || s.command.equals("var") || f.locals.containsKey(s.variable) ? f.locals : f.globals;
                        put(destination, s.variable, s.flags.contains("n") ? value : assignmentValue(value)); break;
                    }
                    case "inc": case "dec": {
                        Map<String, String> destination = f.locals.containsKey(s.variable) ? f.locals : f.globals;
                        double previous = numeric(destination.getOrDefault(s.variable, "0")) ? number(destination.getOrDefault(s.variable, "0")) : 0;
                        put(destination, s.variable, format(previous + (s.command.equals("inc") ? 1 : -1) * number(value))); break;
                    }
                    case "unset":
                        for (String pattern : s.variable.split("\\s+")) {
                            f.locals.keySet().removeIf(name -> glob(pattern.substring(1), name));
                            f.globals.keySet().removeIf(name -> glob(pattern.substring(1), name));
                        }
                        break;
                    case "return": f.result = value; f.returned = true; break;
                    case "halt": throw new Halt();
                    case "echo":
                        f.budget.output();
                        if (value.startsWith("-")) {
                            if (!(value.startsWith("-a ") || value.startsWith("-s "))) throw error(0, "Only echo -a and -s are supported");
                            value = value.substring(3);
                        }
                        f.host.echo(value); break;
                    case "msg": case "notice": {
                        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0
                                || value.indexOf('\1') >= 0) throw error(0, "Control characters are not allowed in messages");
                        String[] parts = value.split("\\s+", 2);
                        if (parts.length != 2 || !safeTarget(parts[0]) || parts[1].isEmpty())
                            throw error(0, "Expected a single IRC target and message");
                        f.budget.output(); f.host.send(parts[0], parts[1], s.command.equals("notice")); break;
                    }
                    case "timer": {
                        if (s.flags.equals("off")) { f.host.cancelTimer(s.timerName.isEmpty() || s.timerName.equals("s") ? "*" : s.timerName); break; }
                        String[] parts = value.split("\\s+", 2);
                        int repetitions = Integer.parseInt(parts[0]), seconds = Integer.parseInt(parts[1]);
                        if (repetitions < 1 || repetitions > 20 || seconds < 1 || seconds > 3600)
                            throw error(0, "Timers require 1–20 repetitions and a 1–3600 second interval; infinite/zero-delay timers are disabled");
                        Statement frozen = new Statement(s.delayed.text, s.line);
                        frozen.command = s.delayed.command; frozen.value = new Literal(s.delayed.value.eval(f));
                        f.host.timer(s.timerName, repetitions, seconds, () -> {
                            Frame timer = new Frame(f.context, f.globals, f.host, new Budget());
                            try { execute(Collections.singletonList(frozen), timer); } catch (Halt ignored) { }
                        }, f.context); break;
                    }
                    default: f.lastResult = call(s.command, value, f, f.depth + 1);
                }
            } catch (IllegalArgumentException ex) {
                if (ex instanceof ScriptException && ((ScriptException) ex).line > 0) throw ex;
                throw error(s.line, ex.getMessage() == null ? "Invalid argument" : ex.getMessage());
            }
        }
    }

    static boolean safeTarget(String target) {
        return !target.isEmpty() && target.length() <= 100 && target.charAt(0) != ':'
                && target.charAt(0) != '$' && target.chars().noneMatch(c -> c <= 32 || c == 127 || c == ',');
    }

    private static void put(Map<String, String> map, String name, String value) {
        if (!map.containsKey(name) && map.size() >= MAX_VARIABLES) throw error(0, "Variable limit exceeded");
        map.put(name, bounded(value));
    }

    private interface Condition { boolean test(Frame f); }
    private Condition condition(String input, int line, int depth) {
        if (depth > 16) throw error(line, "Condition nesting limit exceeded");
        String text = input.trim();
        // mIRC combines comparisons left to right. Explicit parentheses determine grouping.
        int nesting = 0, split = -1; String operator = "";
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) == '(') nesting++;
            if (text.charAt(i) == ')') nesting--;
            String pair = text.substring(i, i + 2);
            if (nesting == 0 && (pair.equals("&&") || pair.equals("||"))) { split = i; operator = pair; i++; }
        }
        if (split >= 0) {
            Condition left = condition(text.substring(0, split), line, depth + 1), right = condition(text.substring(split + 2), line, depth + 1);
            boolean and = operator.equals("&&");
            return f -> { f.budget.step(); return and ? left.test(f) && right.test(f) : left.test(f) || right.test(f); };
        }
        if (text.startsWith("(") && matchingParen(text, 0, line) == text.length() - 1)
            return condition(text.substring(1, text.length() - 1), line, depth + 1);
        if (text.startsWith("!")) { Condition inner = condition(text.substring(1), line, depth + 1); return f -> !inner.test(f); }
        Matcher m = Pattern.compile("(?i)^(.*?)\\s+(!?isincs|!?isin|!?iswmcs|!?iswm|===|==|!=|>=|<=|>|<)\\s+(.*?)$").matcher(text);
        if (m.matches()) {
            Expression left = template(m.group(1), line, 0), right = template(m.group(3), line, 0);
            String op = key(m.group(2));
            return f -> { f.budget.step(); f.v1 = left.eval(f); f.v2 = right.eval(f); return compare(f.v1, op, f.v2); };
        }
        // Unknown operators must not silently become a truthy text expression.
        if (text.contains(" ")) throw error(line, "Unsupported condition operator");
        Expression value = template(text, line, 0);
        return f -> { String result = value.eval(f); return !result.isEmpty() && !result.equals("0") && !result.equalsIgnoreCase("$false"); };
    }
    private static int matchingParen(String text, int start, int line) {
        if (text.isEmpty() || text.charAt(start) != '(') throw error(line, "Expected parenthesized condition");
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '(') depth++;
            if (text.charAt(i) == ')' && --depth == 0) return i;
        }
        throw error(line, "Unclosed parenthesis");
    }

    private static boolean compare(String a, String op, String b) {
        if (op.startsWith("!") && !op.equals("!=")) return !compare(a, op.substring(1), b);
        int order = numeric(a) && numeric(b) ? Double.compare(number(a), number(b)) : a.compareToIgnoreCase(b);
        switch (op) {
            case "==": return order == 0;
            case "===": return a.equals(b);
            case "!=": return order != 0;
            case "isin": return key(b).contains(key(a));
            case "isincs": return b.contains(a);
            case "iswm": return glob(a, b);
            case "iswmcs": return glob(a, b, true);
            case ">": return order > 0;
            case "<": return order < 0;
            case ">=": return order >= 0;
            case "<=": return order <= 0;
            default: throw error(0, "Unsupported comparison");
        }
    }

    /** Linear-space wildcard matching, with input bounded by the event/source limits. No user regex. */
    static boolean glob(String pattern, String text) {
        return glob(pattern, text, false);
    }
    private static boolean glob(String pattern, String text, boolean sensitive) {
        if (!sensitive) { pattern = key(pattern); text = key(text); }
        int p = 0, t = 0, star = -1, mark = 0;
        while (t < text.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == text.charAt(t))) { p++; t++; }
            else if (p < pattern.length() && pattern.charAt(p) == '*') { star = p++; mark = t; }
            else if (star >= 0) { p = star + 1; t = ++mark; }
            else return false;
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }

    private interface Expression { String eval(Frame frame); }

    private Expression template(String input, int line, int depth) {
        if (depth > 16) throw error(line, "Expression nesting limit exceeded");
        List<Expression> parts = new ArrayList<>();
        for (int i = 0; i < input.length();) {
            char c = input.charAt(i);
            if (c == '%' && i + 1 < input.length() && (Character.isLetter(input.charAt(i + 1)) || input.charAt(i + 1) == '_')) {
                int end = i + 2;
                while (end < input.length() && (nameChar(input.charAt(end)) || input.charAt(end) == '.')) end++;
                String name = key(input.substring(i + 1, end));
                parts.add(f -> f.locals.getOrDefault(name, f.globals.getOrDefault(name, ""))); i = end;
            } else if (c == '$' && i + 1 < input.length()) {
                int start = ++i;
                if (input.charAt(i) == '+' && (i + 1 == input.length() || input.charAt(i + 1) != '(')) {
                    // Joining is syntax, not a replacement on expanded user text.
                    while (!parts.isEmpty() && parts.get(parts.size() - 1) instanceof Literal) {
                        Literal previous = (Literal) parts.remove(parts.size() - 1);
                        parts.add(new Literal(previous.value.replaceFirst("\\s+$", ""))); break;
                    }
                    i++; while (i < input.length() && Character.isWhitespace(input.charAt(i))) i++;
                    continue;
                }
                if (Character.isDigit(input.charAt(i))) {
                    while (i < input.length() && Character.isDigit(input.charAt(i))) i++;
                    if (i < input.length() && input.charAt(i) == '-') {
                        i++; while (i < input.length() && Character.isDigit(input.charAt(i))) i++;
                    }
                    String range = input.substring(start, i);
                    parts.add(f -> positional(f.args, range)); continue;
                }
                while (i < input.length() && nameChar(input.charAt(i))) i++;
                if (start == i) throw error(line, "Invalid identifier");
                String name = key(input.substring(start, i));
                if (i < input.length() && input.charAt(i) == '(') {
                    int begin = ++i, nesting = 1;
                    List<Expression> args = new ArrayList<>();
                    for (; i < input.length() && nesting > 0; i++) {
                        char ch = input.charAt(i);
                        if (ch == '(') nesting++;
                        if (ch == ')') nesting--;
                        if ((ch == ',' && nesting == 1) || nesting == 0) {
                            args.add(template(input.substring(begin, i).trim(), line, depth + 1)); begin = i + 1;
                        }
                    }
                    if (nesting != 0) throw error(line, "Unclosed identifier arguments");
                    if (!FUNCTIONS.contains(name) && !aliases.containsKey(name)) throw error(line, "Unsupported identifier: $" + name);
                    parts.add(f -> {
                        List<String> values = new ArrayList<>();
                        for (Expression arg : args) values.add(arg.eval(f));
                        if (aliases.containsKey(name)) return call(name, String.join(" ", values), f, f.depth + 1);
                        return function(name, values);
                    });
                } else {
                    if (!Arrays.asList("nick", "chan", "me", "network", "null", "true", "false", "result", "v1", "v2").contains(name))
                        throw error(line, "Unsupported identifier: $" + name);
                    parts.add(f -> {
                        switch (name) {
                            case "nick": return f.context.nick;
                            case "chan": return f.context.isChannel() ? f.context.channel : "";
                            case "me": return f.context.me;
                            case "network": return f.context.network;
                            case "true": return "$true";
                            case "false": return "$false";
                            case "result": return f.lastResult;
                            case "v1": return f.v1;
                            case "v2": return f.v2;
                            default: return "";
                        }
                    });
                }
            } else {
                int end = i + 1;
                while (end < input.length() && input.charAt(end) != '$' && input.charAt(end) != '%') end++;
                parts.add(new Literal(input.substring(i, end))); i = end;
            }
        }
        return f -> {
            StringBuilder out = new StringBuilder();
            for (Expression part : parts) { f.budget.step(); out.append(part.eval(f)); if (out.length() > MAX_VALUE) throw error(line, "Expanded value is too long"); }
            return out.toString();
        };
    }

    private static boolean nameChar(char c) { return Character.isLetterOrDigit(c) || c == '_'; }

    private static String positional(String arguments, String range) {
        String[] args = arguments.trim().isEmpty() ? new String[0] : arguments.trim().split("\\s+");
        String[] limits = range.split("-", -1);
        int first = Integer.parseInt(limits[0]);
        if (first == 0 && limits.length == 1) return Integer.toString(args.length);
        int last = limits.length == 1 ? first : limits[1].isEmpty() ? args.length : Integer.parseInt(limits[1]);
        if (first < 1 || first > args.length || last < first) return "";
        return String.join(" ", Arrays.copyOfRange(args, first - 1, Math.min(last, args.length)));
    }

    private static String function(String name, List<String> args) {
        int count = name.equals("gettok") ? 3 : Arrays.asList("left", "right").contains(name) ? 2 : 1;
        boolean replace = name.equals("replace") || name.equals("replacecs");
        if (replace ? args.size() < 3 || args.size() % 2 != 1 : args.size() != count)
            throw error(0, "Invalid argument count for $" + name);
        String a = args.get(0);
        switch (name) {
            case "upper": return bounded(a.toUpperCase(Locale.ROOT));
            case "lower": return a.toLowerCase(Locale.ROOT);
            case "len": return Integer.toString(a.length());
            case "calc": return new Arithmetic(a).evaluate();
            case "chr": {
                int code = Integer.parseInt(a);
                if (code < 1 || code > 65535) throw error(0, "Character code must be 1–65535");
                return String.valueOf((char) code);
            }
            case "left": return a.substring(0, sliceCount(a, args.get(1)));
            case "right": return a.substring(a.length() - sliceCount(a, args.get(1)));
            case "replace": case "replacecs": {
                for (int pair = 1; pair < args.size(); pair += 2) {
                    String from = args.get(pair), to = args.get(pair + 1);
                    if (from.isEmpty()) continue;
                    StringBuilder out = new StringBuilder();
                    for (int pos = 0; pos < a.length();) {
                        if (a.regionMatches(!name.equals("replacecs"), pos, from, 0, from.length())) { out.append(to); pos += from.length(); }
                        else out.append(a.charAt(pos++));
                        if (out.length() > MAX_VALUE) throw error(0, "Replacement is too long");
                    }
                    a = out.toString();
                }
                return a;
            }
            case "gettok": {
                int code = Integer.parseInt(args.get(2));
                if (code < 1 || code > 65535) throw error(0, "Invalid token delimiter");
                List<String> tokens = new ArrayList<>();
                for (String token : a.split(Pattern.quote(String.valueOf((char) code)))) if (!token.isEmpty()) tokens.add(token);
                int index = Integer.parseInt(args.get(1));
                if (index == 0) return Integer.toString(tokens.size());
                if (index < 0) index = tokens.size() + index + 1;
                return index >= 1 && index <= tokens.size() ? tokens.get(index - 1) : "";
            }
            default: throw error(0, "Unknown function");
        }
    }

    private static double number(String s) {
        double n = Double.parseDouble(s);
        if (!Double.isFinite(n)) throw error(0, "Expected a finite number");
        return n;
    }
    private static int sliceCount(String text, String count) {
        long n = Long.parseLong(count);
        return (int) Math.max(0, Math.min(text.length(), n < 0 ? text.length() + n : n));
    }
    private static boolean numeric(String value) { return value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"); }
    private static String format(double n) {
        if (!Double.isFinite(n)) throw error(0, "Invalid arithmetic result");
        return java.math.BigDecimal.valueOf(n).setScale(5, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
    private static String assignmentValue(String text) {
        if (text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)\\s+[+*/%^\\-]\\s+[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))
            return new Arithmetic(text).evaluate();
        return text;
    }

    private static final class Arithmetic {
        final String text; int at, depth;
        Arithmetic(String text) { this.text = text; }
        String evaluate() {
            double result = sum(); spaces();
            if (at != text.length() || !Double.isFinite(result)) throw error(0, "Invalid arithmetic expression");
            return format(result);
        }
        void spaces() { while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++; }
        boolean take(char c) { spaces(); if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        double sum() { double n = product(); while (true) { if (take('+')) n += product(); else if (take('-')) n -= product(); else return n; } }
        double product() { double n = power(); while (true) { if (take('*')) n *= power(); else if (take('/')) n /= power(); else if (take('%')) n %= power(); else return n; } }
        double power() { double n = atom(); if (take('^')) { if (++depth > 32) throw error(0, "Arithmetic nesting limit exceeded"); try { n = Math.pow(n, power()); } finally { depth--; } } return n; }
        double atom() {
            if (++depth > 32) throw error(0, "Arithmetic nesting limit exceeded");
            try {
                if (take('+')) return atom(); if (take('-')) return -atom();
                if (take('(')) { double n = sum(); if (!take(')')) throw error(0, "Missing arithmetic closing parenthesis"); return n; }
                spaces(); int start = at;
                while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) at++;
                if (start == at) throw error(0, "Expected a number");
                return number(text.substring(start, at));
            } finally { depth--; }
        }
    }

    private static final class Literal implements Expression {
        final String value; Literal(String value) { this.value = value; }
        public String eval(Frame f) { return value; }
    }
    private static final class Budget {
        int steps = 4096, outputs = 16;
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100);
        void step() {
            if (--steps < 0) throw error(0, "Execution step limit exceeded");
            if (System.nanoTime() > deadline) throw error(0, "Execution time limit exceeded");
        }
        void output() { if (--outputs < 0) throw error(0, "Output limit exceeded"); }
    }
    private static final class Frame {
        final ScriptContext context; final Map<String, String> globals, locals = new LinkedHashMap<>();
        final Host host; final Budget budget; String args = "", result = "", lastResult = "", v1 = "", v2 = ""; int depth; boolean returned;
        Set<String> calls = new HashSet<>();
        Frame(ScriptContext context, Map<String, String> globals, Host host, Budget budget) { this.context = context; this.globals = globals; this.host = host; this.budget = budget; }
        Frame child(String args, int depth) { Frame f = new Frame(context, globals, host, budget); f.args = bounded(args); f.depth = depth; f.calls = calls; return f; }
    }
    private static final class Handler {
        final String pattern, target; final List<Statement> body;
        Handler(String pattern, String target, List<Statement> body) { this.pattern = pattern; this.target = target; this.body = body; }
    }
    private static final class Statement {
        final String text; final int line; List<Statement> body;
        String command, variable, flags = "", timerName; Expression value; Condition condition; Statement delayed;
        Statement(String text, int line) { this.text = text; this.line = line; }
    }
    private static final class Halt extends RuntimeException {
        Halt() { super(null, null, false, false); }
    }
    private static final class Token {
        final String text; final int line;
        Token(String text, int line) { this.text = text; this.line = line; }
    }

    /** Braces and pipes are structural outside identifier parentheses. Semicolon starts a line comment. */
    private static final class Parser {
        final List<Token> tokens = new ArrayList<>(); int at;
        Parser(String source) {
            StringBuilder text = new StringBuilder(); int line = 1, start = 1, parens = 0;
            boolean comment = false;
            for (int i = 0; i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == '\r') continue;
                if (comment && c != '\n') continue;
                if (c == ';' && text.toString().trim().isEmpty()) { comment = true; continue; }
                if (c == '(' && (parens > 0 || syntaxParen(text))) parens++;
                if (c == ')' && parens > 0) parens--;
                if (c == '\n' || (parens == 0 && (c == '{' || c == '}' || c == '|'))) {
                    String value = text.toString().trim();
                    if (!value.isEmpty()) tokens.add(new Token(value, start));
                    text.setLength(0);
                    if (c == '{' || c == '}') tokens.add(new Token(String.valueOf(c), line));
                    if (c == '\n') { if (parens != 0) throw error(line, "Unclosed parentheses"); line++; comment = false; }
                    start = line;
                } else { if (text.length() == 0) start = line; text.append(c); }
            }
            if (parens != 0) throw error(line, "Unclosed parentheses");
            if (!text.toString().trim().isEmpty()) tokens.add(new Token(text.toString().trim(), start));
        }
        private static boolean syntaxParen(StringBuilder text) {
            int at = text.length() - 1;
            while (at >= 0 && nameChar(text.charAt(at))) at--;
            if (at >= 0 && text.charAt(at) == '$') return true;
            String prefix = text.toString().trim().toLowerCase(Locale.ROOT);
            return prefix.equals("if") || prefix.equals("elseif") || prefix.endsWith(" if") || prefix.endsWith(" elseif");
        }
        List<Statement> block(boolean nested, int depth) {
            if (depth > 16) throw error(at < tokens.size() ? tokens.get(at).line : 0, "Block nesting limit exceeded");
            List<Statement> result = new ArrayList<>();
            while (at < tokens.size()) {
                Token token = tokens.get(at++);
                if (token.text.equals("}")) { if (!nested) throw error(token.line, "Unexpected }"); return result; }
                if (token.text.equals("{")) throw error(token.line, "Unexpected {");
                Statement s = new Statement(token.text, token.line);
                if (at < tokens.size() && tokens.get(at).text.equals("{")) { at++; s.body = block(true, depth + 1); }
                result.add(s);
            }
            if (nested) throw error(tokens.isEmpty() ? 1 : tokens.get(tokens.size() - 1).line, "Missing }");
            return result;
        }
    }
}
