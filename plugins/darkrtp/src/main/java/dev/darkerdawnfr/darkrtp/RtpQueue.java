package dev.darkerdawnfr.darkrtp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** /rtpqueue: players wait here; the first two are sent to the same random spot together. */
public class RtpQueue {
    private final LinkedHashSet<UUID> waiting = new LinkedHashSet<>();

    public boolean join(UUID id) {
        return waiting.add(id);
    }

    public boolean leave(UUID id) {
        return waiting.remove(id);
    }

    public boolean contains(UUID id) {
        return waiting.contains(id);
    }

    public int size() {
        return waiting.size();
    }

    public List<UUID> all() {
        return new ArrayList<>(waiting);
    }

    /** The two who waited longest, taken out of the queue; null if fewer than two are waiting. */
    public UUID[] pair() {
        if (waiting.size() < 2) return null;
        Iterator<UUID> it = waiting.iterator();
        UUID a = it.next();
        it.remove();
        UUID b = it.next();
        it.remove();
        return new UUID[]{a, b};
    }
}
