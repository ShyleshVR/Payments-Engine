package com.shylesh.ledger_service.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Where a page of the audit listing ended: the last transaction's creation time and id. Clients
 * get it as an opaque string and send it back unchanged for the next page.
 */
record AuditCursor(LocalDateTime time, UUID id) {

    static class Invalid extends RuntimeException {
    }

    /** Before the first transaction of a period: everything in it sorts after this. */
    static AuditCursor startOf(LocalDateTime from) {
        return new AuditCursor(from, new UUID(0, 0));
    }

    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((time + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    /** @throws Invalid if this isn't a cursor of ours, or belongs to another period */
    static AuditCursor decode(String encoded, LocalDateTime from, LocalDateTime to) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", 2);
            AuditCursor cursor = new AuditCursor(LocalDateTime.parse(parts[0]), UUID.fromString(parts[1]));
            if (cursor.time.isBefore(from) || !cursor.time.isBefore(to)) {
                throw new Invalid();
            }
            return cursor;
        } catch (IllegalArgumentException | DateTimeParseException | ArrayIndexOutOfBoundsException e) {
            throw new Invalid();
        }
    }
}
