/* ABOUTME: Verifies trusted session binding and atomic hard-cap reservations. */
package ai.ultimate.tools.builtin;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibreOfficeJdbcComputeBudgetTest {

    @Test
    void reservesWorstCaseAllocationAgainstTrustedSession() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID sessionId = UUID.randomUUID();
        when(jdbc.update(
                anyString(),
                any(),
                any(),
                any())).thenReturn(1);

        var budget = new LibreOfficeJdbcComputeBudget(jdbc);
        boolean accepted = budget.reserve(
                new ToolContext(Map.of(
                        LibreOfficeJdbcComputeBudget.SESSION_ID_CONTEXT_KEY,
                        sessionId)),
                new BigDecimal("150.00"),
                1,
                Duration.ofSeconds(60));

        assertThat(accepted).isTrue();
        verify(jdbc).update(
                anyString(),
                eq(sessionId),
                eq(new BigDecimal("37.50")),
                eq(new BigDecimal("150.00")));
    }

    @Test
    void fourOperationBatchReservesEntireHardCap() {
        assertThat(LibreOfficeJdbcComputeBudget.reservationUsd(
                4,
                Duration.ofSeconds(60)))
                .isEqualByComparingTo("150.00");
    }

    @Test
    void rejectsMissingOrModelVisibleSessionIdentityBeforeDatabase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var budget = new LibreOfficeJdbcComputeBudget(jdbc);

        assertThat(budget.reserve(
                new ToolContext(Map.of()),
                new BigDecimal("150.00"),
                1,
                Duration.ofSeconds(60))).isFalse();
        assertThat(budget.reserve(
                new ToolContext(Map.of(
                        LibreOfficeJdbcComputeBudget.SESSION_ID_CONTEXT_KEY,
                        "not-a-uuid")),
                new BigDecimal("150.00"),
                1,
                Duration.ofSeconds(60))).isFalse();

        verify(jdbc, never()).update(
                anyString(),
                any(),
                any(),
                any());
    }

    @Test
    void refusesAnyCallerCapAboveAbsoluteOneHundredFiftyDollars() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var budget = new LibreOfficeJdbcComputeBudget(jdbc);

        assertThat(budget.reserve(
                new ToolContext(Map.of(
                        LibreOfficeJdbcComputeBudget.SESSION_ID_CONTEXT_KEY,
                        UUID.randomUUID())),
                new BigDecimal("150.01"),
                1,
                Duration.ofSeconds(60))).isFalse();

        verify(jdbc, never()).update(
                anyString(),
                any(),
                any(),
                any());
    }

    @Test
    void failedOrContendedLedgerWriteFailsClosed() {
        UUID sessionId = UUID.randomUUID();
        ToolContext context = new ToolContext(Map.of(
                LibreOfficeJdbcComputeBudget.SESSION_ID_CONTEXT_KEY,
                sessionId));

        JdbcTemplate contended = mock(JdbcTemplate.class);
        when(contended.update(anyString(), any(), any(), any()))
                .thenReturn(0);
        assertThat(new LibreOfficeJdbcComputeBudget(contended).reserve(
                context,
                new BigDecimal("150.00"),
                1,
                Duration.ofSeconds(60))).isFalse();

        JdbcTemplate failed = mock(JdbcTemplate.class);
        when(failed.update(anyString(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThat(new LibreOfficeJdbcComputeBudget(failed).reserve(
                context,
                new BigDecimal("150.00"),
                1,
                Duration.ofSeconds(60))).isFalse();
    }
}
