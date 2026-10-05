package com.opentext.automatedruntimetuning.audit;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Bounded in-memory record of tuning decisions and operator actions.
 *
 * <p>This exists so that a change can be explained after the fact without grepping
 * application logs. It is deliberately bounded and in-memory: it is an operational aid,
 * not the system of record. Prometheus holds the durable history.
 */
@Component
public class AuditLog {

    private static final int CAPACITY = 300;

    private final Deque<AuditEntry> entries = new ArrayDeque<>(CAPACITY);

    public synchronized void record(AuditEntry entry) {
        if (entries.size() >= CAPACITY) {
            entries.removeLast();
        }
        entries.addFirst(entry);
    }

    /** Records an operator action or a subsystem event with no before/after value. */
    public void event(String loop, String mode, String detail) {
        record(new AuditEntry(Instant.now(), loop, mode, null, null, null, false, detail));
    }

    public synchronized List<AuditEntry> recent(int limit) {
        int bounded = Math.max(1, Math.min(limit, CAPACITY));
        return entries.stream().limit(bounded).toList();
    }

    public synchronized void clear() {
        entries.clear();
    }
}