package ai.ultimate.agents;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentOrchestratorAsyncStartTest {

    @Mock
    private AgentExecutor executor;

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private AgentStepRepository stepRepository;

    @Mock
    private R2dbcEntityTemplate r2dbcEntityTemplate;

    @Test
    void asyncStartReturnsPersistedIdentityAndExecutesSameAgent() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Agent persisted = Agent.create(userId, sessionId, "Test goal");
        Agent running = persisted.withRunning();

        when(r2dbcEntityTemplate.insert(any(Agent.class)))
                .thenReturn(Mono.just(persisted));
        when(r2dbcEntityTemplate.update(any(Agent.class)))
                .thenReturn(Mono.just(running));
        when(executor.execute(any(Agent.class), eq(userId)))
                .thenReturn(Flux.empty());

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                executor,
                agentRepository,
                stepRepository,
                r2dbcEntityTemplate);

        StepVerifier.create(orchestrator.startAgentAsync(
                        "Test goal", userId, sessionId))
                .assertNext(agent -> {
                    assertEquals(persisted.id(), agent.id());
                    assertEquals(AgentStatus.PENDING, agent.status());
                })
                .verifyComplete();

        verify(executor).execute(
                org.mockito.ArgumentMatchers.argThat(
                        agent -> agent.id().equals(persisted.id())
                                && agent.status() == AgentStatus.RUNNING),
                eq(userId));
    }
}
