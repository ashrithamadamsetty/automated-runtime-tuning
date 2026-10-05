package com.opentext.automatedruntimetuning.audit;

import java.time.Instant;

/**
 * One line of the tuning audit trail. Nullable numeric fields distinguish a resource
 * change from an operator action, which has no before/after value.
 */
public record AuditEntry(
        Instant at,
        String loop,
        String mode,
        Integer from,
        Integer want,
        Integer to,
        boolean applied,
        String detail
) {
}