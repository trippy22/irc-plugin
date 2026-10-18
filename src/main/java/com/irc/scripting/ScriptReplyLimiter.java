package com.irc.scripting;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Fixed limits: scripts and persisted settings cannot raise these ceilings. Worker-confined. */
final class ScriptReplyLimiter {
    private final LongSupplier clock;
    private final ArrayDeque<Long> all = new ArrayDeque<>();
    private final Map<String, ArrayDeque<Long>> scripts = new HashMap<>();
    private final Map<String, Long> duplicates = new LinkedHashMap<>();
    ScriptReplyLimiter(LongSupplier clock) { this.clock = clock; }
    void acquire(String script, ScriptContext context, String target, String text) {
        long now = clock.getAsLong(), minute = TimeUnit.MINUTES.toNanos(1);
        prune(all, now, minute);
        ArrayDeque<Long> own = scripts.computeIfAbsent(script, ignored -> new ArrayDeque<>());
        prune(own, now, minute);
        duplicates.entrySet().removeIf(e -> now - e.getValue() >= minute);
        String fingerprint = context.network + "\0" + target.toLowerCase(Locale.ROOT) + "\0" + text;
        if (duplicates.containsKey(fingerprint)) throw new IllegalArgumentException("Duplicate script reply suppressed for 60 seconds");
        if (all.size() >= 10 || own.size() >= 6 || (!all.isEmpty() && now - all.peekLast() < TimeUnit.SECONDS.toNanos(3)))
            throw new IllegalArgumentException("Script reply limit: 3-second gap, 6 per script/minute, 10 total/minute");
        all.addLast(now); own.addLast(now); duplicates.put(fingerprint, now);
        // Evict idle script buckets so repeated import/remove cannot grow this map forever.
        Iterator<Map.Entry<String, ArrayDeque<Long>>> iterator = scripts.entrySet().iterator();
        while (iterator.hasNext()) { ArrayDeque<Long> times = iterator.next().getValue(); prune(times, now, minute); if (times.isEmpty()) iterator.remove(); }
    }
    private static void prune(ArrayDeque<Long> times, long now, long window) {
        while (!times.isEmpty() && now - times.peekFirst() >= window) times.removeFirst();
    }
}
