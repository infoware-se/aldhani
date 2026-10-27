package se.infoware.domino.aldhani;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-flight JSON-RPC calls, so a later notifications/cancelled can stop one. */
final class CallTracker {
    static final class Flight {
        final Thread thread = Thread.currentThread();
        final AtomicBoolean cancelled = new AtomicBoolean();
    }

    private final ConcurrentHashMap<String, Flight> inflight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> early = new ConcurrentHashMap<>();

    Flight start(String key) {
        Flight flight = new Flight();
        if (early.remove(key) != null) {
            flight.cancelled.set(true);
        }
        inflight.put(key, flight);
        return flight;
    }

    void finish(String key) {
        inflight.remove(key);
        early.remove(key);
    }

    void cancel(String key) {
        if (key == null) {
            return;
        }
        Flight flight = inflight.get(key);
        if (flight == null) {
            if (early.size() > 100) {
                early.clear();
            }
            early.put(key, Boolean.TRUE);
            return;
        }
        flight.cancelled.set(true);
        flight.thread.interrupt();
    }

    boolean running(String key) {
        return key != null && inflight.containsKey(key);
    }

    static String key(Object id) {
        if (id == null) {
            return null;
        }
        if (id instanceof Number number) {
            return "n:" + number.longValue();
        }
        return "s:" + id;
    }
}
