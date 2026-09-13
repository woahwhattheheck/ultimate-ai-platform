package ai.ultimate.agents;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Coordinates persisted agent lifecycle and execution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final AgentExecutor executor;
    private final AgentRepository agentRepository;
    private final AgentStepRepository stepRepository;
    private final R2dbcEntityTemplate r2dbcEntityTemplate;

    /**
     * Persist an agent and stream its execution events.
     */
    public Flux<AgentEvent> startAgent(
            String goal,
            UUID userId,
            UUID sessionId) {

        long startTime = System.currentTimeMillis();
        Agent newAgent = Agent.create(userId, sessionId, goal);

        return r2dbcEntityTemplate
                .insert(newAgent)
                .flatMapMany(agent ->
                        runPersistedAgent(
                                agent,
                                userId,
                                startTime));
    }

    /**
     * Persist an agent and launch execution independently.
     */
    public Mono<Agent> startAgentAsync(
            String goal,
            UUID userId,
            UUID sessionId) {

        long startTime = System.currentTimeMillis();
        Agent newAgent = Agent.create(userId, sessionId, goal);

        return r2dbcEntityTemplate
                .insert(newAgent)
                .doOnNext(agent ->
                        runPersistedAgent(
                                agent,
                                userId,
                                startTime)
                                .subscribe(
                                        event -> {
                                            // Lifecycle writes are composed
                                            // inside the execution stream.
                                        },
                                        error -> log.error(
                                                "Async agent error: "
                                                        + "id={} error={}",
                                                agent.id(),
                                                error.getMessage(),
                                                error)));
    }

    /**
     * Transition a PENDING agent to RUNNING and execute it.
     */
    private Flux<AgentEvent> runPersistedAgent(
            Agent pendingAgent,
            UUID userId,
            long startTime) {

        return agentRepository
                .updateStatus(
                        pendingAgent.id(),
                        AgentStatus.PENDING.name(),
                        AgentStatus.RUNNING.name(),
                        pendingAgent.stepCount(),
                        null,
                        null,
                        null)
                .switchIfEmpty(Mono.error(
                        new IllegalStateException(
                                "Agent RUNNING transition "
                                        + "returned no row count: "
                                        + pendingAgent.id())))
                .flatMap(rows -> {
                    if (rows == 0) {
                        log.info(
                                "Agent launch skipped: id={} "
                                        + "status changed before RUNNING",
                                pendingAgent.id());
                        return Mono.<Agent>empty();
                    }
                    if (rows != 1) {
                        return Mono.error(
                                new IllegalStateException(
                                        "Agent RUNNING transition "
                                                + "updated unexpected rows="
                                                + rows
                                                + " id="
                                                + pendingAgent.id()));
                    }
                    Agent runningAgent = pendingAgent.withRunning();
                    log.info(
                            "Agent started: id={} user={}",
                            runningAgent.id(),
                            userId);
                    return Mono.just(runningAgent);
                })
                .flatMapMany(agent ->
                        executePersistedAgent(
                                agent,
                                userId,
                                startTime));
    }

    /**
     * Execute while composing every terminal write into the Flux.
     */
    private Flux<AgentEvent> executePersistedAgent(
            Agent agent,
            UUID userId,
            long startTime) {

        Flux<AgentEvent> execution = executor
                .execute(agent, userId)
                .onErrorResume(error ->
                        persistFailure(
                                agent,
                                errorMessage(error))
                                .onErrorMap(persistenceError -> {
                                    persistenceError.addSuppressed(error);
                                    return persistenceError;
                                })
                                .then(Mono.<AgentEvent>error(error)));

        return execution.concatMap(event ->
                persistTerminalEvent(
                        agent,
                        event,
                        startTime));
    }

    /**
     * Persist a terminal event before exposing it downstream.
     */
    private Mono<AgentEvent> persistTerminalEvent(
            Agent agent,
            AgentEvent event,
            long startTime) {

        if (event.type() == AgentEvent.EventType.FINAL) {
            long duration =
                    System.currentTimeMillis() - startTime;
            int durationMs =
                    (int) Math.min(duration, Integer.MAX_VALUE);

            return countPersistedSteps(agent.id())
                    .flatMap(stepCount ->
                            transitionTerminal(
                                    agent,
                                    AgentStatus.COMPLETED,
                                    stepCount,
                                    event.data(),
                                    null,
                                    durationMs))
                    .thenReturn(event);
        }

        if (event.type() == AgentEvent.EventType.ERROR) {
            return persistFailure(agent, event.data())
                    .thenReturn(event);
        }

        return Mono.just(event);
    }

    /**
     * Persist FAILED with the authoritative step count.
     */
    private Mono<Void> persistFailure(
            Agent agent,
            String message) {

        return countPersistedSteps(agent.id())
                .flatMap(stepCount ->
                        transitionTerminal(
                                agent,
                                AgentStatus.FAILED,
                                stepCount,
                                null,
                                message,
                                null));
    }

    /**
     * Convert the persisted count without truncation.
     */
    private Mono<Integer> countPersistedSteps(UUID agentId) {
        return stepRepository
                .countByAgentId(agentId)
                .switchIfEmpty(Mono.error(
                        new IllegalStateException(
                                "Agent step count returned no value: "
                                        + agentId)))
                .flatMap(count -> {
                    if (count < 0
                            || count > Integer.MAX_VALUE) {
                        return Mono.error(
                                new IllegalStateException(
                                        "Agent step count out of range: "
                                                + count
                                                + " id="
                                                + agentId));
                    }
                    return Mono.just(count.intValue());
                });
    }

    /**
     * Apply a terminal compare-and-set and validate its row count.
     */
    private Mono<Void> transitionTerminal(
            Agent agent,
            AgentStatus target,
            int stepCount,
            String finalAnswer,
            String errorMessage,
            Integer durationMs) {

        return agentRepository
                .updateStatus(
                        agent.id(),
                        AgentStatus.RUNNING.name(),
                        target.name(),
                        stepCount,
                        finalAnswer,
                        errorMessage,
                        durationMs)
                .switchIfEmpty(Mono.error(
                        new IllegalStateException(
                                "Agent " + target
                                        + " transition returned "
                                        + "no row count: "
                                        + agent.id())))
                .flatMap(rows -> {
                    if (rows == 1) {
                        log.info(
                                "Agent {}: id={} steps={}",
                                target,
                                agent.id(),
                                stepCount);
                        return Mono.<Void>empty();
                    }
                    if (rows == 0) {
                        log.warn(
                                "Agent {} lost status race: id={}",
                                target,
                                agent.id());
                        return Mono.<Void>empty();
                    }
                    return Mono.<Void>error(
                            new IllegalStateException(
                                    "Agent " + target
                                            + " transition updated "
                                            + "unexpected rows="
                                            + rows
                                            + " id="
                                            + agent.id()));
                });
    }

    private String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    /**
     * Get all agents for a user, newest first.
     */
    public Flux<Agent> getUserAgents(UUID userId) {
        return agentRepository
                .findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Get one owned agent with ordered steps.
     */
    public Mono<AgentWithSteps> getAgent(
            UUID agentId,
            UUID userId) {

        return agentRepository
                .findByIdAndUserId(agentId, userId)
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Agent not found")))
                .flatMap(agent ->
                        stepRepository
                                .findByAgentIdOrderByStepIndexAsc(
                                        agentId)
                                .collectList()
                                .map(steps ->
                                        new AgentWithSteps(
                                                agent,
                                                steps)));
    }

    /**
     * Cancel a non-terminal owned agent.
     */
    public Mono<Void> cancelAgent(
            UUID agentId,
            UUID userId) {

        return agentRepository
                .findByIdAndUserId(agentId, userId)
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Agent not found")))
                .flatMap(agent -> {
                    if (agent.status() == AgentStatus.COMPLETED
                            || agent.status() == AgentStatus.FAILED
                            || agent.status()
                            == AgentStatus.CANCELLED) {

                        return Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Agent is already "
                                                + "in terminal state: "
                                                + agent.status()));
                    }

                    log.info(
                            "Cancelling agent: id={} user={}",
                            agentId,
                            userId);

                    return agentRepository
                            .updateStatus(
                                    agentId,
                                    agent.status().name(),
                                    AgentStatus.CANCELLED.name(),
                                    agent.stepCount(),
                                    null,
                                    null,
                                    null)
                            .switchIfEmpty(Mono.error(
                                    new IllegalStateException(
                                            "Agent cancellation returned "
                                                    + "no row count: "
                                                    + agentId)))
                            .flatMap(rows -> {
                                if (rows == 1) {
                                    return Mono.<Void>empty();
                                }
                                if (rows == 0) {
                                    return Mono.error(
                                            new ResponseStatusException(
                                                    HttpStatus.CONFLICT,
                                                    "Agent status changed "
                                                            + "before cancellation"));
                                }
                                return Mono.error(
                                        new IllegalStateException(
                                                "Agent cancellation updated "
                                                        + "unexpected rows="
                                                        + rows
                                                        + " id="
                                                        + agentId));
                            });
                });
    }

    /**
     * Agent and its ordered steps.
     */
    public record AgentWithSteps(
            Agent agent,
            List<AgentStep> steps) {}
}
