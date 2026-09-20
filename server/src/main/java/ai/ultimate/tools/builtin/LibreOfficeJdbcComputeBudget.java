/* ABOUTME: PostgreSQL-backed hard session-window allocation gate for LibreOffice. */
package ai.ultimate.tools.builtin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Atomically reserves worst-case LibreOffice compute allocation per trusted chat
 * session. The model cannot provide the session id or the allocation amount.
 *
 * A maximum LibreOffice operation can run for 60 seconds. The default allocation
 * rate is deliberately conservative: USD 0.625 per reserved second, so one
 * worst-case operation reserves USD 37.50 and the maximum four-operation batch
 * reserves exactly the USD 150 hard cap. Reservations are not refunded on
 * process failure; this prevents retry loops from bypassing the cap.
 */
@Component
public final class LibreOfficeJdbcComputeBudget implements LibreOfficeComputeBudget {
    static final String SESSION_ID_CONTEXT_KEY = "ultimate.sessionId";
    static final BigDecimal HARD_CAP_USD = new BigDecimal("150.00");
    static final BigDecimal ALLOCATION_USD_PER_SECOND = new BigDecimal("0.625");

    private static final String RESERVE_SQL = """
            INSERT INTO libreoffice_compute_budgets
                (session_id, window_started_at, reserved_usd, updated_at)
            VALUES (?, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (session_id) DO UPDATE
            SET window_started_at = CASE
                    WHEN libreoffice_compute_budgets.window_started_at
                         <= CURRENT_TIMESTAMP - INTERVAL '30 minutes'
                    THEN CURRENT_TIMESTAMP
                    ELSE libreoffice_compute_budgets.window_started_at
                END,
                reserved_usd = CASE
                    WHEN libreoffice_compute_budgets.window_started_at
                         <= CURRENT_TIMESTAMP - INTERVAL '30 minutes'
                    THEN EXCLUDED.reserved_usd
                    ELSE libreoffice_compute_budgets.reserved_usd
                         + EXCLUDED.reserved_usd
                END,
                updated_at = CURRENT_TIMESTAMP
            WHERE libreoffice_compute_budgets.window_started_at
                      <= CURRENT_TIMESTAMP - INTERVAL '30 minutes'
               OR libreoffice_compute_budgets.reserved_usd
                      + EXCLUDED.reserved_usd <= ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public LibreOfficeJdbcComputeBudget(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean reserve(
            ToolContext context,
            BigDecimal maximumSessionUsd,
            int operations,
            Duration maximumOperationRuntime) {
        UUID sessionId = trustedSessionId(context);
        if (sessionId == null
                || maximumSessionUsd == null
                || maximumSessionUsd.signum() <= 0
                || maximumSessionUsd.compareTo(HARD_CAP_USD) > 0
                || operations <= 0
                || maximumOperationRuntime == null
                || maximumOperationRuntime.isZero()
                || maximumOperationRuntime.isNegative()) {
            return false;
        }

        BigDecimal requested = reservationUsd(
                operations,
                maximumOperationRuntime);
        if (requested.signum() <= 0
                || requested.compareTo(maximumSessionUsd) > 0) {
            return false;
        }

        try {
            return jdbcTemplate.update(
                    RESERVE_SQL,
                    sessionId,
                    requested,
                    maximumSessionUsd) == 1;
        } catch (RuntimeException failure) {
            // Billing/allocation state is authoritative. Any uncertainty denies work.
            return false;
        }
    }

    static BigDecimal reservationUsd(
            int operations,
            Duration maximumOperationRuntime) {
        if (operations <= 0
                || maximumOperationRuntime == null
                || maximumOperationRuntime.isZero()
                || maximumOperationRuntime.isNegative()) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal milliseconds = BigDecimal.valueOf(
                maximumOperationRuntime.toMillis());
        BigDecimal seconds = milliseconds.divide(
                BigDecimal.valueOf(1_000),
                3,
                RoundingMode.CEILING);
        return seconds
                .multiply(ALLOCATION_USD_PER_SECOND)
                .multiply(BigDecimal.valueOf(operations))
                .setScale(2, RoundingMode.CEILING);
    }

    private static UUID trustedSessionId(ToolContext context) {
        if (context == null) {
            return null;
        }
        Map<String, Object> values = context.getContext();
        if (values == null) {
            return null;
        }
        Object raw = values.get(SESSION_ID_CONTEXT_KEY);
        if (raw instanceof UUID sessionId) {
            return sessionId;
        }
        if (raw instanceof String text) {
            try {
                return UUID.fromString(text);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
