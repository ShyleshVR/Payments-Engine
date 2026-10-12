package com.shylesh.processor_simulator.web;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Where a settlement report page ended: the last row's sort time and id, and (for authorizations)
 * which of the report's three passes it belonged to. Clients get it as an opaque string and send
 * it back unchanged to get the next page.
 */
record ReportCursor(Pass pass, LocalDateTime time, String id) {

    /** The passes of the authorization report, in order (see AuthorizationRepository). */
    enum Pass {
        CREATED, CAPTURED, VOIDED;

        Pass next() {
            return this == VOIDED ? null : values()[ordinal() + 1];
        }
    }

    static class Invalid extends RuntimeException {
    }

    /** Before the first row of a pass: every row of the period sorts after it. */
    static ReportCursor startOf(Pass pass, LocalDateTime from) {
        return new ReportCursor(pass, from, "");
    }

    String encode() {
        String plain = pass.name() + "|" + time + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws Invalid if this isn't a cursor of ours, or belongs to another period */
    static ReportCursor decode(String encoded, LocalDateTime from, LocalDateTime to) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", 3);
            ReportCursor cursor = new ReportCursor(Pass.valueOf(parts[0]), LocalDateTime.parse(parts[1]), parts[2]);
            if (cursor.time.isBefore(from) || !cursor.time.isBefore(to)) {
                throw new Invalid();
            }
            return cursor;
        } catch (IllegalArgumentException | DateTimeParseException | ArrayIndexOutOfBoundsException e) {
            throw new Invalid();
        }
    }
}
