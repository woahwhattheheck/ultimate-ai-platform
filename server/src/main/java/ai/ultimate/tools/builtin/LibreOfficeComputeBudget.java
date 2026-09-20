/* ABOUTME: Host-owned dollar allocation boundary for LibreOffice execution. */
package ai.ultimate.tools.builtin;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Server-side integration point; never populated from model arguments.
 *
 * Implementations resolve a trusted session/window from ToolContext and
 * atomically reserve the full worst-case allocation of ALL operations in the
 * batch. Reservations include prior and concurrent use and fail closed on
 * missing identity or ledger errors.
 *
 * The default application wiring is LibreOfficeJdbcComputeBudget, which uses
 * the authenticated chat session id placed into ToolContext by AiOrchestrator
 * and a PostgreSQL row-level atomic reservation. Hosts may replace this bean
 * with a stricter shared allocator without changing the tool schema.
 */
@FunctionalInterface
public interface LibreOfficeComputeBudget {
    boolean reserve(ToolContext context, BigDecimal maximumSessionUsd,
            int operations, Duration maximumOperationRuntime);
}
