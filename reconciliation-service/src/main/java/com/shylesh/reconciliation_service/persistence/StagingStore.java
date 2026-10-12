package com.shylesh.reconciliation_service.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shylesh.reconciliation_service.domain.Records;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Where a run keeps the day's records while it works (table reconciliation_staging), so that a
 * day of any size costs the same memory: the sources are written here a page at a time, and read
 * back a chunk of payments (or payouts) at a time. A record is stored as JSON under the payment
 * or payout it belongs to; a record that belongs to neither can't be matched and is not stored.
 */
@Repository
@RequiredArgsConstructor
public class StagingStore {

    enum Kind {AUTHORIZATION, REFUND, TRANSFER, LEDGER}

    /** The records staged for a range of payments, or of payouts. */
    public record Staged(List<Records.Authorization> authorizations, List<Records.Refund> refunds,
                         List<Records.Transfer> transfers, List<Records.LedgerTransaction> ledgerTransactions) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void addAuthorizations(UUID runId, List<Records.Authorization> page) {
        add(runId, Kind.AUTHORIZATION, page, Records.Authorization::paymentId, a -> null);
    }

    public void addRefunds(UUID runId, List<Records.Refund> page) {
        add(runId, Kind.REFUND, page, Records.Refund::paymentId, r -> null);
    }

    public void addTransfers(UUID runId, List<Records.Transfer> page) {
        add(runId, Kind.TRANSFER, page, t -> null, Records.Transfer::payoutId);
    }

    /** A ledger transaction is a payment's or a payout's, never both (a constraint in the ledger). */
    public void addLedgerTransactions(UUID runId, List<Records.LedgerTransaction> page) {
        add(runId, Kind.LEDGER, page, Records.LedgerTransaction::paymentId, Records.LedgerTransaction::payoutId);
    }

    /** One insert statement for the whole page: the columns travel as three arrays. */
    private <T> void add(UUID runId, Kind kind, List<T> page, Function<T, UUID> paymentId, Function<T, UUID> payoutId) {
        List<UUID> paymentIds = new ArrayList<>(page.size());
        List<UUID> payoutIds = new ArrayList<>(page.size());
        List<String> payloads = new ArrayList<>(page.size());
        for (T item : page) {
            UUID payment = paymentId.apply(item);
            UUID payout = payoutId.apply(item);
            if (payment == null && payout == null) {
                continue;
            }
            paymentIds.add(payment);
            payoutIds.add(payout);
            payloads.add(write(item));
        }
        if (payloads.isEmpty()) {
            return;
        }
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO reconciliation_staging (run_id, kind, payment_id, payout_id, payload)
                    SELECT ?, ?, r.payment_id, r.payout_id, r.payload
                    FROM unnest(?::uuid[], ?::uuid[], ?::text[]) WITH ORDINALITY AS r (payment_id, payout_id, payload, position)
                    ORDER BY r.position
                    """);
            statement.setObject(1, runId);
            statement.setString(2, kind.name());
            statement.setArray(3, connection.createArrayOf("uuid", paymentIds.toArray(UUID[]::new)));
            statement.setArray(4, connection.createArrayOf("uuid", payoutIds.toArray(UUID[]::new)));
            statement.setArray(5, connection.createArrayOf("text", payloads.toArray(String[]::new)));
            return statement;
        });
    }

    /** The run's next payments in id order, after the given one (null: from the start). */
    public List<UUID> paymentIdsAfter(UUID runId, UUID after, int limit) {
        return idsAfter("payment_id", runId, after, limit);
    }

    public List<UUID> payoutIdsAfter(UUID runId, UUID after, int limit) {
        return idsAfter("payout_id", runId, after, limit);
    }

    private List<UUID> idsAfter(String column, UUID runId, UUID after, int limit) {
        // the database's order of uuids, not Java's: ids are only ever compared here
        String sql = "SELECT DISTINCT " + column + " FROM reconciliation_staging WHERE run_id = ? AND " + column + " IS NOT NULL"
                + (after == null ? "" : " AND " + column + " > ?") + " ORDER BY " + column + " LIMIT ?";
        return after == null
                ? jdbcTemplate.queryForList(sql, UUID.class, runId, limit)
                : jdbcTemplate.queryForList(sql, UUID.class, runId, after, limit);
    }

    /**
     * Everything staged for the payments from first to last (as paymentIdsAfter orders them), each
     * kind in the order its source listed it.
     */
    public Staged ofPayments(UUID runId, UUID first, UUID last) {
        return load("payment_id", runId, first, last);
    }

    public Staged ofPayouts(UUID runId, UUID first, UUID last) {
        return load("payout_id", runId, first, last);
    }

    private Staged load(String column, UUID runId, UUID first, UUID last) {
        Staged staged = new Staged(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        jdbcTemplate.query("SELECT kind, payload FROM reconciliation_staging WHERE run_id = ? AND " + column + " >= ? AND "
                + column + " <= ? ORDER BY seq", row -> {
            String payload = row.getString("payload");
            switch (Kind.valueOf(row.getString("kind"))) {
                case AUTHORIZATION -> staged.authorizations().add(read(payload, Records.Authorization.class));
                case REFUND -> staged.refunds().add(read(payload, Records.Refund.class));
                case TRANSFER -> staged.transfers().add(read(payload, Records.Transfer.class));
                case LEDGER -> staged.ledgerTransactions().add(read(payload, Records.LedgerTransaction.class));
            }
        }, runId, first, last);
        return staged;
    }

    /**
     * Tells the planner what was just staged. The table was empty a moment ago; without this the
     * chunk queries are planned for an empty table.
     */
    public void analyze() {
        jdbcTemplate.execute("ANALYZE reconciliation_staging");
    }

    public long count(UUID runId) {
        Long rows = jdbcTemplate.queryForObject("SELECT count(*) FROM reconciliation_staging WHERE run_id = ?", Long.class, runId);
        return rows == null ? 0 : rows;
    }

    /**
     * Two statements, one per index (each index holds only the rows with its id, and every row
     * has one of the two). A small run's rows are then found through the indexes, however much
     * the table holds for other runs or in rows not vacuumed yet; "WHERE run_id = ?" alone can
     * use neither index and reads the whole table.
     */
    public int delete(UUID runId) {
        return jdbcTemplate.update("DELETE FROM reconciliation_staging WHERE run_id = ? AND payment_id IS NOT NULL", runId)
                + jdbcTemplate.update("DELETE FROM reconciliation_staging WHERE run_id = ? AND payout_id IS NOT NULL", runId);
    }

    private String write(Object item) {
        try {
            return objectMapper.writeValueAsString(item);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not stage " + item, e);
        }
    }

    private <T> T read(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read a staged " + type.getSimpleName(), e);
        }
    }
}
