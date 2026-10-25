package com.example.a2atest.a2a;

import java.util.List;

/**
 * What an agent gets for one turn of a task.
 *
 * @param message   the client's message for this turn
 * @param taskId    the task the turn belongs to
 * @param contextId the conversation the task belongs to, as the client named it (key memory by {@link #memoryKey()})
 * @param history   the task's messages before this turn (empty for a new task)
 * @param principal who is calling: {@code "anonymous"} for agents without authentication, otherwise an id
 *                  derived from the caller's API key
 */
public record RequestContext(A2a.Message message, String taskId, String contextId, List<A2a.Message> history,
                             String principal) {

    /**
     * The key to keep this conversation's chat memory under. It combines the caller and the {@code contextId},
     * so a caller who reuses another caller's {@code contextId} gets a separate conversation instead of reading
     * or poisoning theirs. Agents use it, not the raw {@code contextId}, as their {@code @MemoryId}.
     */
    public String memoryKey() {
        return principal + "/" + contextId;
    }

    /** True when the client continues a task that was waiting for input. */
    public boolean isContinuation() {
        return !history.isEmpty();
    }
}
