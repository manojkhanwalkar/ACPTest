package com.example.a2atest.a2a;

import java.util.Map;

/**
 * An agent that can be hosted by an {@link A2aServer}. A simple agent implements {@link #reply} (text in, text
 * out, the task completes). An agent that streams progress, produces artifacts, asks for more input or
 * returns structured data overrides {@link #execute} and drives the task through its {@link TaskUpdater}.
 */
public interface A2aAgent {

    /** The agent's card; {@code url} is the JSON-RPC endpoint the server is reachable at. */
    A2a.AgentCard card(String url);

    /**
     * Handles one incoming message synchronously.
     *
     * @param message   the client's message
     * @param contextId the conversation's memory key ({@link RequestContext#memoryKey()}: the caller plus the
     *                  A2A {@code contextId}), to keep per-conversation memory
     * @return the text of the agent's reply
     */
    default String reply(A2a.Message message, String contextId) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not implement reply()");
    }

    /**
     * Works on a task until it reaches a terminal or interrupted state. Runs on its own virtual thread; the
     * server marks the task failed if this method throws or returns without settling the task.
     */
    default void execute(RequestContext request, TaskUpdater task) {
        task.complete(reply(request.message(), request.memoryKey()));
    }

    /** Agent-specific counters merged into the server's {@code GET /metrics} output. */
    default Map<String, Object> metrics() {
        return Map.of();
    }
}
