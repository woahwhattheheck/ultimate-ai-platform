package ai.ultimate.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorRunningExecutionCancellationTest {

    @Mock
    private AgentExecutor executor;

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private AgentStepRepository stepRepository;

    @Mock
    private R2dbcEntityTemplate r2dbcEntityTemplate;

    private AgentOrchestrator orchestrator() {
        return new AgentOrchestrator(
                executor,
                agentRepository,
                stepRepository,
                r2dbcEntityTemplate);
    }

    @Test
    void successfulRunningCancellationCancelsActiveExecutorPublisher() {
        UUID userId = UUID.randomUUID();
        Agent persisted = Agent.create(userId, null, "long-running work");
        Agent running = persisted.withRunning();
        AtomicBoolean subscribed = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();

        when(r2dbcEntityTemplate.insert(any(Agent.class)))
                .thenReturn(Mono.just(persisted));
        when(agentRepository.updateStatus(
                persisted.id(),
                AgentStatus.PENDING.name(),
                AgentStatus.RUNNING.name(),
                persisted.stepCount(),
                null,
                null,
                null))
                .thenReturn(Mono.just(1));
        when(executor.execute(any(Agent.class), eq(userId)))
                .thenReturn(Flux.<AgentEvent>never()
                        .doOnSubscribe(ignored -> subscribed.set(true))
                        .doOnCancel(() -> cancelled.set(true)));
        when(agentRepository.findByIdAndUserId(persisted.id(), userId))
                .thenReturn(Mono.just(running));
        when(agentRepository.updateStatus(
                persisted.id(),
                AgentStatus.RUNNING.name(),
                AgentStatus.CANCELLED.name(),
                running.stepCount(),
                null,
                null,
                null))
                .thenReturn(Mono.just(1));

        AgentOrchestrator orchestrator = orchestrator();

        StepVerifier.create(orchestrator.startAgentAsync(
                        persisted.goal(), userId, null))
                .expectNextMatches(agent -> agent.id().equals(persisted.id()))
                .verifyComplete();
        assertTrue(subscribed.get());
        assertFalse(cancelled.get());

        StepVerifier.create(orchestrator.cancelAgent(persisted.id(), userId))
                .verifyComplete();

        assertTrue(cancelled.get());
    }

    @Test
    void lostCancellationCasDoesNotCancelWinningExecution() {
        UUID userId = UUID.randomUUID();
        Agent persisted = Agent.create(userId, null, "racing work");
        Agent running = persisted.withRunning();
        AtomicBoolean cancelled = new AtomicBoolean();
        Sinks.One<Boolean> release = Sinks.one();

        when(r2dbcEntityTemplate.insert(any(Agent.class)))
                .thenReturn(Mono.just(persisted));
        when(agentRepository.updateStatus(
                persisted.id(),
                AgentStatus.PENDING.name(),
                AgentStatus.RUNNING.name(),
                persisted.stepCount(),
                null,
                null,
                null))
                .thenReturn(Mono.just(1));
        when(executor.execute(any(Agent.class), eq(userId)))
                .thenReturn(Flux.<AgentEvent>never()
                        .doOnCancel(() -> cancelled.set(true))
                        .takeUntilOther(release.asMono()));
        when(agentRepository.findByIdAndUserId(persisted.id(), userId))
                .thenReturn(Mono.just(running));
        when(agentRepository.updateStatus(
                persisted.id(),
                AgentStatus.RUNNING.name(),
                AgentStatus.CANCELLED.name(),
                running.stepCount(),
                null,
                null,
                null))
                .thenReturn(Mono.just(0));

        AgentOrchestrator orchestrator = orchestrator();

        StepVerifier.create(orchestrator.startAgentAsync(
                        persisted.goal(), userId, null))
                .expectNextMatches(agent -> agent.id().equals(persisted.id()))
                .verifyComplete();

        StepVerifier.create(orchestrator.cancelAgent(persisted.id(), userId))
                .expectErrorSatisfies(error -> {
                    assertTrue(error instanceof ResponseStatusException);
                    ResponseStatusException responseError =
                            (ResponseStatusException) error;
                    assertEquals(HttpStatus.CONFLICT,
                            responseError.getStatusCode());
                })
                .verify();

        assertFalse(cancelled.get());
        release.tryEmitValue(Boolean.TRUE);
        assertTrue(cancelled.get());
    }

    @Test
    void finalEventIsSuppressedWhenCompletedCasLoses() {
        UUID userId = UUID.randomUUID();
        Agent persisted = Agent.create(userId, null, "race final");
        AgentEvent finalEvent = AgentEvent.finalAnswer(0, "must not leak");

        when(r2dbcEntityTemplate.insert(any(Agent.class)))
                .thenReturn(Mono.just(persisted));
        when(agentRepository.updateStatus(
                persisted.id(),
                AgentStatus.PENDING.name(),
                AgentStatus.RUNNING.name(),
                persisted.stepCount(),
                null,
                null,
                null))
                .thenReturn(Mono.just(1));
        when(executor.execute(any(Agent.class), eq(userId)))
                .thenReturn(Flux.just(finalEvent));
        when(stepRepository.countByAgentId(persisted.id()))
                .thenReturn(Mono.just(0L));
        when(agentRepository.updateStatus(
                eq(persisted.id()),
                eq(AgentStatus.RUNNING.name()),
                eq(AgentStatus.COMPLETED.name()),
                eq(0),
                eq(finalEvent.data()),
                isNull(),
                any(Integer.class)))
                .thenReturn(Mono.just(0));

        StepVerifier.create(orchestrator().startAgent(
                        persisted.goal(), userId, null))
                .verifyComplete();
    }

    @Test
    void executorFailureIsSuppressedWhenFailedCasLoses() {
        UUID userId = UUID.randomUUID();
        Agent persisted = Agent.create(userId, null, "race failure");
        IllegalStateException failure =
                new IllegalStateException("provider failed after cancellation");

        when(r2dbcEntityTemplate.insert(any(Agent.class)))
                .thenReturn(Mono.just(persisted));
        when(agentRepository.updateStatus(
                persisted.id(),
                AgentStatus.PENDING.name(),
                AgentStatus.RUNNING.name(),
                persisted.stepCount(),
                null,
                null,
                null))
                .thenReturn(Mono.just(1));
        when(executor.execute(any(Agent.class), eq(userId)))
                .thenReturn(Flux.error(failure));
        when(stepRepository.countByAgentId(persisted.id()))
                .thenReturn(Mono.just(0L));
        when(agentRepository.updateStatus(
                persisted.id(),
                AgentStatus.RUNNING.name(),
                AgentStatus.FAILED.name(),
                0,
                null,
                failure.getMessage(),
                null))
                .thenReturn(Mono.just(0));

        StepVerifier.create(orchestrator().startAgent(
                        persisted.goal(), userId, null))
                .verifyComplete();
    }

    @Test
    void finalEventStillFlowsWhenCompletedCasWins() {
        UUID userId = UUID.randomUUID();
        Agent persisted = Agent.create(userId, null, "normal final");
        AgentEvent finalEvent = AgentEvent.finalAnswer(0, "winner");

        when(r2dbcEntityTemplate.insert(any(Agent.class)))
                .thenReturn(Mono.just(persisted));
        when(agentRepository.updateStatus(
                persisted.id(),
                AgentStatus.PENDING.name(),
                AgentStatus.RUNNING.name(),
                persisted.stepCount(),
                null,
                null,
                null))
                .thenReturn(Mono.just(1));
        when(executor.execute(any(Agent.class), eq(userId)))
                .thenReturn(Flux.just(finalEvent));
        when(stepRepository.countByAgentId(persisted.id()))
                .thenReturn(Mono.just(0L));
        when(agentRepository.updateStatus(
                eq(persisted.id()),
                eq(AgentStatus.RUNNING.name()),
                eq(AgentStatus.COMPLETED.name()),
                eq(0),
                eq(finalEvent.data()),
                isNull(),
                any(Integer.class)))
                .thenReturn(Mono.just(1));

        StepVerifier.create(orchestrator().startAgent(
                        persisted.goal(), userId, null))
                .expectNext(finalEvent)
                .verifyComplete();
    }
}
