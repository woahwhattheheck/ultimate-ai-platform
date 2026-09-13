package ai.ultimate.agents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorCancellationTest {

    @Mock
    private AgentExecutor executor;

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private AgentStepRepository stepRepository;

    @Mock
    private R2dbcEntityTemplate r2dbcEntityTemplate;

    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new AgentOrchestrator(
                executor,
                agentRepository,
                stepRepository,
                r2dbcEntityTemplate);
    }

    @Test
    void cancellationCompletesWhenCasUpdatesExactlyOneRow() {
        UUID userId = UUID.randomUUID();
        Agent running = Agent.create(userId, null, "cancel me").withRunning();

        when(agentRepository.findByIdAndUserId(running.id(), userId))
                .thenReturn(Mono.just(running));
        when(agentRepository.updateStatus(
                running.id(),
                AgentStatus.RUNNING.name(),
                AgentStatus.CANCELLED.name(),
                running.stepCount(),
                null,
                null,
                null))
                .thenReturn(Mono.just(1));

        StepVerifier.create(orchestrator.cancelAgent(running.id(), userId))
                .verifyComplete();
    }

    @Test
    void cancellationReturnsConflictWhenConcurrentTransitionWins() {
        UUID userId = UUID.randomUUID();
        Agent running = Agent.create(userId, null, "racing completion").withRunning();

        when(agentRepository.findByIdAndUserId(running.id(), userId))
                .thenReturn(Mono.just(running));
        when(agentRepository.updateStatus(
                running.id(),
                AgentStatus.RUNNING.name(),
                AgentStatus.CANCELLED.name(),
                running.stepCount(),
                null,
                null,
                null))
                .thenReturn(Mono.just(0));

        StepVerifier.create(orchestrator.cancelAgent(running.id(), userId))
                .expectErrorSatisfies(error -> {
                    assertTrue(error instanceof ResponseStatusException);
                    ResponseStatusException responseError =
                            (ResponseStatusException) error;
                    assertEquals(HttpStatus.CONFLICT, responseError.getStatusCode());
                    assertTrue(responseError.getReason()
                            .contains("status changed before cancellation"));
                })
                .verify();
    }

    @Test
    void cancellationFailsClosedOnUnexpectedRowCount() {
        UUID userId = UUID.randomUUID();
        Agent running = Agent.create(userId, null, "unexpected rows").withRunning();

        when(agentRepository.findByIdAndUserId(running.id(), userId))
                .thenReturn(Mono.just(running));
        when(agentRepository.updateStatus(
                running.id(),
                AgentStatus.RUNNING.name(),
                AgentStatus.CANCELLED.name(),
                running.stepCount(),
                null,
                null,
                null))
                .thenReturn(Mono.just(2));

        StepVerifier.create(orchestrator.cancelAgent(running.id(), userId))
                .expectErrorMatches(error ->
                        error instanceof IllegalStateException
                                && error.getMessage().contains(
                                        "updated unexpected rows=2"))
                .verify();
    }

    @Test
    void cancellationFailsClosedWhenRepositoryReturnsNoRowCount() {
        UUID userId = UUID.randomUUID();
        Agent running = Agent.create(userId, null, "missing row count").withRunning();

        when(agentRepository.findByIdAndUserId(running.id(), userId))
                .thenReturn(Mono.just(running));
        when(agentRepository.updateStatus(
                running.id(),
                AgentStatus.RUNNING.name(),
                AgentStatus.CANCELLED.name(),
                running.stepCount(),
                null,
                null,
                null))
                .thenReturn(Mono.empty());

        StepVerifier.create(orchestrator.cancelAgent(running.id(), userId))
                .expectErrorMatches(error ->
                        error instanceof IllegalStateException
                                && error.getMessage().contains(
                                        "returned no row count"))
                .verify();
    }
}
