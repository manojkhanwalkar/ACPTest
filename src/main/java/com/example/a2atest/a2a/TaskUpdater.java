package com.example.a2atest.a2a;

import java.util.List;
import java.util.UUID;

/**
 * Lets an agent move its task through the A2A lifecycle. Every call is recorded on the task (visible through
 * {@code tasks/get}) and published to {@code message/stream} subscribers. Once the task is terminal (for
 * example after a {@code tasks/cancel}) further updates are ignored, so agents check {@link #isCanceled()}
 * between steps of long work.
 */
public final class TaskUpdater {

    private final TaskStore.TaskRecord task;

    TaskUpdater(TaskStore.TaskRecord task) {
        this.task = task;
    }

    public String taskId() {
        return task.id();
    }

    public String contextId() {
        return task.contextId();
    }

    /** Progress update; the task stays {@code working}. */
    public void working(String text) {
        task.updateStatus(A2a.TaskState.WORKING, agentMessage(List.of(A2a.Part.text(text))));
    }

    /** Adds a complete artifact (one chunk). */
    public void artifact(String name, List<A2a.Part> parts) {
        task.addArtifact(new A2a.Artifact(UUID.randomUUID().toString(), name, null, parts), false, true);
    }

    /**
     * Adds one chunk of a streamed artifact. The first chunk for {@code artifactId} creates the artifact,
     * later ones append to it; {@code lastChunk} marks the end.
     */
    public void artifactChunk(String artifactId, String name, String text, boolean lastChunk) {
        task.addArtifact(new A2a.Artifact(artifactId, name, null, List.of(A2a.Part.text(text))), true, lastChunk);
    }

    public void complete(String text) {
        complete(List.of(A2a.Part.text(text)));
    }

    public void complete(List<A2a.Part> parts) {
        task.updateStatus(A2a.TaskState.COMPLETED, agentMessage(parts));
    }

    /** Asks the client a question; the client answers by sending a message with this task's id. */
    public void requireInput(String question) {
        task.updateStatus(A2a.TaskState.INPUT_REQUIRED, agentMessage(List.of(A2a.Part.text(question))));
    }

    /** The agent refuses the request (e.g. a guardrail blocked it). */
    public void reject(String reason) {
        task.updateStatus(A2a.TaskState.REJECTED, agentMessage(List.of(A2a.Part.text(reason))));
    }

    public void fail(String reason) {
        task.updateStatus(A2a.TaskState.FAILED, agentMessage(List.of(A2a.Part.text(reason))));
    }

    public boolean isCanceled() {
        return task.state() == A2a.TaskState.CANCELED;
    }

    private A2a.Message agentMessage(List<A2a.Part> parts) {
        return A2a.Message.agent(parts, task.contextId(), task.id());
    }
}
