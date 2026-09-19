package ai.ultimate.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTerminalPersistenceTest {

    @Mock
    private AgentExecutor executor;

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private AgentStepRepository stepRepository;

    @Mock
    private R2dbcEntityTemplate r2dbcEntityTemplate;

    @Test
    void finalEventWaitsForCompletionPersistence() {
        UUID userId = UUID.randomUUID();
        Agent persisted = Agent.create(
                userId,
                UUID.randomUUID(),
                "Finish after persistence");
        AgentEvent finalEvent =
                AgentEvent.finalAnswer(3, "complete");
        Sinks.One<Integer> completionCas = Sinks.one();

        when(r2dbcEntityTemplate.insert(any(Agent.class)))
                .thenReturn(Mono.just(persisted));
        stubRunningTransition(persisted);
        when(executor.execute(
                any(Agent.class),
                eq(userId)))
                .thenReturn(Flux.just(finalEvent));
        when(stepRepository.countByAgentId(persisted.id()))
                .thenReturn(Mono.just(3L));
        when(agentRepository.updateStatus(
                eq(persisted.id()),
                eq(AgentStatus.RUNNING.name()),
                eq(AgentStatus.COMPLETED.name()),
                eq(3),
                eq("complete"),
                isNull(),
                anyInt()))
                .thenReturn(completionCas.asMono());

        AgentOrchestrator orchestrator = orchestrator();

        StepVerifier.create(orchestrator.startAgent(
                        persisted.goal(),
                        userId,
                        persisted.sessionId()))
                .expectSubscription()
                .then(() -> verify(agentRepository)
                        .updateStatus(
                                eq(persisted.id()),
                                eq(AgentStatus.RUNNING.name()),
                                eq(AgentStatus.COMPLETED.name()),
                                eq(3),
                                eq("complete"),
                                isNull(),
                                anyInt()))
                .expectNoEvent(Duration.ofMillis(25))
                .then(() -> completionCas.tryEmitValue(1))
                .expectNext(finalEvent)
                .verifyComplete();
    }

    @Test
    void completionPersistenceFailurePreventsFinalEmission() {
        UUID userId = UUID.randomUUID();
        Agent persisted = Agent.create(
                userId,
                UUID.randomUUID(),
                "Expose persistence failures");
        AgentEvent finalEvent =
                AgentEvent.finalAnswer(1, "answer");
        RuntimeException databaseFailure =
                new RuntimeException("database unavailable");

        when(r2dbcEntityTemplate.insert(any(Agent.class)))
                .thenReturn(Mono.just(persisted));
        stubRunningTransition(persisted);
        when(executor.execute(
                any(Agent.class),
                eq(userId)))
                .thenReturn(Flux.just(finalEvent));
        when(stepRepository.countByAgentId(persisted.id()))
                .thenReturn(Mono.just(1L));
        when(agentRepository.updateStatus(
                eq(persisted.id()),
                eq(AgentStatus.RUNNING.name()),
                eq(AgentStatus.COMPLETED.name()),
                eq(1),
                eq("answer"),
                isNull(),
                anyInt()))
                .thenReturn(Mono.error(databaseFailure));

        AgentOrchestrator orchestrator = orchestrator();

        StepVerifier.create(orchestrator.startAgent(
                        persisted.goal(),
                        userId,
                        persisted.sessionId()))
                .expectErrorSatisfies(error ->
                        assertSame(databaseFailure, error))
                .verify();
    }

    @Test
    void streamErrorWaitsForFailedPersistence() {
        UUID userId = UUID.randomUUID();
        Agent persisted = Agent.create(
                userId,
                UUID.randomUUID(),
                "Fail after persistence");
        RuntimeException executionFailure =
                new RuntimeException("executor exploded");
        Sinks.One<Integer> failureCas = Sinks.one();

        when(r2dbcEntityTemplate.insert(any(Agent.class)))
                .thenReturn(Mono.just(persisted));
        stubRunningTransition(persisted);
        when(executor.execute(
                any(Agent.class),
                eq(userId)))
                .thenReturn(Flux.error(executionFailure));
        when(stepRepository.countByAgentId(persisted.id()))
                .thenReturn(Mono.just(7L));
        when(agentRepository.updateStatus(
                persisted.id(),
                AgentStatus.RUNNING.name(),
                AgentStatus.FAILED.name(),
                7,
                null,
                executionFailure.getMessage(),
                null))
                .thenReturn(failureCas.asMono());

        AgentOrchestrator orchestrator = orchestrator();

        StepVerifier.create(orchestrator.startAgent(
                        persisted.goal(),
                        userId,
                        persisted.sessionId()))
                .expectSubscription()
                .then(() -> verify(agentRepository)
                        .updateStatus(
                                persisted.id(),
                                AgentStatus.RUNNING.name(),
                                AgentStatus.FAILED.name(),
                                7,
                                null,
                                executionFailure.getMessage(),
                                null))
                .expectNoEvent(Duration.ofMillis(25))
                .then(() -> failureCas.tryEmitValue(1))
                .expectErrorSatisfies(error ->
                        assertSame(executionFailure, error))
                .verify();
    }

    private void stubRunningTransition(Agent persisted) {
        when(agentRepository.updateStatus(
                persisted.id(),
                AgentStatus.PENDING.name(),
                AgentStatus.RUNNING.name(),
                persisted.stepCount(),
                null,
                null,
                null))
                .thenReturn(Mono.just(1));
    }

    private AgentOrchestrator orchestrator() {
        return new AgentOrchestrator(
                executor,
                agentRepository,
                stepRepository,
                r2dbcEntityTemplate);
    }
}
