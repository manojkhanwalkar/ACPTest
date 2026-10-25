package com.example.a2atest.a2a;

import java.util.List;

/**
 * What an agent gets for one turn of a task.
 *
 * @param message   the client's message for this turn
 * @param taskId    the task the turn belongs to
 * @param contextId the conversation the task belongs to (the agent's chat-memory key)
 * @param history   the task's messages before this turn (empty for a new task)
 */
public record RequestContext(A2a.Message message, String taskId, String contextId, List<A2a.Message> history) {

    /** True when the client continues a task that was waiting for input. */
    public boolean isContinuation() {
        return !history.isEmpty();
    }
}
