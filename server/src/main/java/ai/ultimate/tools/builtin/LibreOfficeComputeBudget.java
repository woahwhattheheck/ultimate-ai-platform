/* ABOUTME: Host-owned dollar allocation boundary for LibreOffice execution. */
package ai.ultimate.tools.builtin;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Server-side integration point; never populated from model arguments.
 *
 * A host implementation must resolve a trusted session/window from context and
 * atomically reserve the full worst-case cost of ALL operations in the batch.
 * It must include prior and concurrent reservations, enforce the supplied USD
 * cap, and reject absent/expired context, unknown tariffs and ledger failures.
 * The reservation must remain charged if execution fails; refunds, if any, need
 * independently verified usage. It must work across every serving instance.
 *
 * Current main has no authoritative USD/session-window ledger. No permissive
 * default implementation is provided: the tool rejects execution without one.
 * Implementing this interface is not by itself proof of a real billing cap.
 */
@FunctionalInterface
public interface LibreOfficeComputeBudget {
    boolean reserve(ToolContext context, BigDecimal maximumSessionUsd,
            int operations, Duration maximumOperationRuntime);
}
