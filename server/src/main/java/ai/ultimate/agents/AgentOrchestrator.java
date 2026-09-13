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
 * Coordinates the full agent lifecycle.
 *
 * RESPONSIBILITIES:
 * 1. Create Agent entity in DB (PENDING)
 * 2. Transition to RUNNING
 * 3. Delegate execution to AgentExecutor
 * 4. Monitor event stream for FINAL/ERROR
 * 5. Update Agent to COMPLETED or FAILED
 * 6. Return Flux<AgentEvent> for SSE streaming
 *
 * WHY separate from AgentExecutor:
 * AgentExecutor handles the ReACT loop only.
 * AgentOrchestrator handles the DB lifecycle:
 * create → run → complete/fail.
 * Separation keeps each class focused.
 *
 * STREAMING:
 * Returns the Flux<AgentEvent> directly so
 * AgentController can stream events to the client
 * while the agent executes in the background.
 * The doOnNext/doOnComplete/doOnError side effects
 * update the DB without blocking the stream.
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
     * Start an agent task and stream its events.
     *
     * FLOW:
     * 1. Create Agent (PENDING) in DB
     * 2. Transition to RUNNING in DB
     * 3. Start AgentExecutor loop
     * 4. Stream events to caller
     * 5. On FINAL event → update DB to COMPLETED
     * 6. On ERROR event → update DB to FAILED
     *
     * The returned Flux is cold — execution only
     * starts when someone subscribes to it
     * (when the SSE connection is established).
     *
     * @param goal      user's task description
     * @param userId    authenticated user
     * @param sessionId optional chat session link
     * @return Flux<AgentEvent> streamed step events
     */
    public Flux<AgentEvent> startAgent(
            String goal,
            UUID userId,
            UUID sessionId) {

        long startTime =
                System.currentTimeMillis();

        Agent newAgent = Agent.create(
                userId, sessionId, goal);

        return r2dbcEntityTemplate
                .insert(newAgent)
                .flatMapMany(agent ->
                        runPersistedAgent(
                                agent,
                                userId,
                                startTime));
    }

    /**
     * Persist an agent and launch its execution without
     * tying the caller to the event stream.
     *
     * The returned Agent is the exact PENDING entity inserted
     * into the database. Callers can safely return its ID and
     * poll it while the independently subscribed execution
     * transitions that same entity to RUNNING and terminal state.
     *
     * @param goal      user's task description
     * @param userId    authenticated user
     * @param sessionId optional chat session link
     * @return Mono containing the persisted Agent identity
     */
    public Mono<Agent> startAgentAsync(
            String goal,
            UUID userId,
            UUID sessionId) {

        long startTime =
                System.currentTimeMillis();

        Agent newAgent = Agent.create(
                userId, sessionId, goal);

        return r2dbcEntityTemplate
                .insert(newAgent)
                .doOnNext(agent ->
                        runPersistedAgent(
                                agent,
                                userId,
                                startTime)
                                .subscribe(
                                        event -> {
                                            // Execution side effects are
                                            // tracked inside the stream.
                                        },
                                        error -> log.error(
                                                "Async agent error: "
                                                        + "id={} error={}",
                                                agent.id(),
                                                error.getMessage(),
                                                error)));
    }

    /**
     * Atomically claim a previously persisted PENDING agent for
     * execution, then maintain its terminal lifecycle state.
     *
     * Cancellation and startup both use compare-and-set status
     * transitions. If cancellation wins while the agent is still
     * PENDING, this claim updates zero rows and execution never
     * reaches AgentExecutor.
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
                .flatMapMany(rows -> {
                    if (rows == 0) {
                        log.info(
                                "Agent start skipped after concurrent "
                                        + "lifecycle transition: id={} user={}",
                                pendingAgent.id(),
                                userId);
                        return Flux.empty();
                    }

                    Agent agent = pendingAgent.withRunning();
                    log.info(
                            "Agent started: id={} user={}",
                            agent.id(),
                            userId);

                    // Execute the ReACT loop
                    return executor.execute(agent, userId)

                            // Track FINAL event to
                            // update DB to COMPLETED
                            .doOnNext(event -> {

                                if (event.type() ==
                                        AgentEvent.EventType.FINAL) {

                                    long duration =
                                            System.currentTimeMillis()
                                                    - startTime;
                                    int durationMs =
                                            (int) Math.min(
                                                    duration,
                                                    Integer.MAX_VALUE);

                                    // Async DB update
                                    // count steps then complete
                                    stepRepository
                                            .countByAgentId(
                                                    agent.id())
                                            .flatMap(count ->
                                                    agentRepository
                                                            .updateStatus(
                                                                    agent.id(),
                                                                    AgentStatus.RUNNING.name(),
                                                                    AgentStatus.COMPLETED.name(),
                                                                    count.intValue(),
                                                                    event.data(),
                                                                    null,
                                                                    durationMs))
                                            .doOnSuccess(updatedRows -> {
                                                if (updatedRows > 0) {
                                                    log.info(
                                                            "Agent COMPLETED: "
                                                                    + "id={} "
                                                                    + "duration={}ms",
                                                            agent.id(),
                                                            duration);
                                                } else {
                                                    log.warn(
                                                            "Agent COMPLETED "
                                                                    + "but 0 rows updated "
                                                                    + "(race condition?): "
                                                                    + "id={}",
                                                            agent.id());
                                                }
                                            })
                                            .doOnError(e ->
                                                    log.error(
                                                            "Failed to complete "
                                                                    + "agent: id={} "
                                                                    + "error={}",
                                                            agent.id(),
                                                            e.getMessage()))
                                            .subscribe();
                                }
                            })

                            // On ERROR event → FAILED
                            .doOnNext(event -> {
                                if (event.type() ==
                                        AgentEvent.EventType.ERROR) {

                                    agentRepository
                                            .updateStatus(
                                                    agent.id(),
                                                    AgentStatus.RUNNING.name(),
                                                    AgentStatus.FAILED.name(),
                                                    agent.stepCount(),
                                                    null,
                                                    event.data(),
                                                    null)
                                            .doOnError(e ->
                                                    log.error(
                                                            "Failed to mark agent "
                                                                    + "as FAILED: id={}",
                                                            agent.id()))
                                            .subscribe();
                                }
                            })

                            // On stream error → FAILED
                            .doOnError(error -> {
                                log.error(
                                        "Agent stream error: "
                                                + "id={} error={}",
                                        agent.id(),
                                        error.getMessage());

                                agentRepository
                                        .updateStatus(
                                                agent.id(),
                                                AgentStatus.RUNNING.name(),
                                                AgentStatus.FAILED.name(),
                                                agent.stepCount(),
                                                null,
                                                error.getMessage(),
                                                null)
                                        .subscribe();
                            });
                });
    }

    /**
     * Get all agents for a user (newest first).
     * Steps NOT loaded — use getAgent() for steps.
     *
     * @param userId authenticated user
     * @return Flux<Agent> agents without steps
     */
    public Flux<Agent> getUserAgents(UUID userId) {
        return agentRepository
                .findByUserIdOrderByCreatedAtDesc(
                        userId);
    }

    /**
     * Get a single agent with its steps.
     * Ownership verified — 404 if not found or wrong user.
     *
     * @param agentId the agent to retrieve
     * @param userId  must be the owner
     * @return Mono<AgentWithSteps> agent + ordered steps
     */
    public Mono<AgentWithSteps> getAgent(
            UUID agentId, UUID userId) {

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
                                                agent, steps)));
    }

    /**
     * Cancel a running agent.
     * Can only cancel PENDING or RUNNING agents.
     * Ownership verified before cancellation.
     *
     * @param agentId agent to cancel
     * @param userId  must be the owner
     * @return Mono<Void> completes when cancelled
     */
    public Mono<Void> cancelAgent(
            UUID agentId, UUID userId) {

        return agentRepository
                .findByIdAndUserId(agentId, userId)
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Agent not found")))
                .flatMap(agent -> {

                    // Can only cancel non-terminal agents
                    if (agent.status() ==
                            AgentStatus.COMPLETED
                            || agent.status() ==
                            AgentStatus.FAILED
                            || agent.status() ==
                            AgentStatus.CANCELLED) {

                        return Mono.error(
                                new ResponseStatusException(
                                        HttpStatus.CONFLICT,
                                        "Agent is already "
                                                + "in terminal state: "
                                                + agent.status()));
                    }

                    log.info(
                            "Cancelling agent: id={} "
                                    + "user={}",
                            agentId, userId);

                    return agentRepository
                            .updateStatus(
                                    agentId,
                                    agent.status().name(),
                                    AgentStatus.CANCELLED.name(),
                                    agent.stepCount(),
                                    null,
                                    null,
                                    null)
                            .then();
                });
    }

    // ── Inner Record ──────────────────────────────

    /**
     * Agent + its steps combined.
     * Used for single agent detail endpoint.
     *
     * @param agent the agent entity
     * @param steps all steps in order
     */
    public record AgentWithSteps(
            Agent agent,
            List<AgentStep> steps) {}
}
