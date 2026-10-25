package com.example.a2atest.agents.report;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.agents.RemoteAgent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Client of the {@link ReportAgent}: streams a report as it is written, or starts one in the background and
 * polls or cancels it.
 */
public final class ReportClientAgent extends RemoteAgent {

    /**
     * A streamed report.
     *
     * @param text     the report assembled from the artifact chunks
     * @param state    the final task state
     * @param progress the status messages received while the agent worked
     * @param chunks   number of artifact chunks received
     */
    public record Report(String taskId, String text, A2a.TaskState state, List<String> progress, int chunks) {
    }

    public ReportClientAgent(A2aClient a2a) {
        super(a2a);
    }

    /** Requests a report with {@code message/stream}; {@code onChunk} gets each piece of text as it arrives. */
    public Report stream(String topic, Consumer<String> onChunk) {
        if (!card().supportsStreaming()) {
            throw new IllegalStateException(card().name() + " does not stream");
        }
        StringBuilder text = new StringBuilder();
        List<String> progress = new ArrayList<>();
        String[] taskId = {null};
        A2a.TaskState[] state = {A2a.TaskState.UNKNOWN};
        int[] chunks = {0};
        a2a.streamMessage(card(), A2a.Message.user(topic, contextId), event -> {
            switch (event) {
                case A2a.Task task -> {
                    taskId[0] = task.id();
                    state[0] = task.status().state();
                }
                case A2a.TaskStatusUpdateEvent update -> {
                    state[0] = update.status().state();
                    if (update.status().message() != null && !update.isFinal()) {
                        progress.add(update.status().message().text());
                    }
                }
                case A2a.TaskArtifactUpdateEvent update -> {
                    String chunk = update.artifact().text();
                    if (!chunk.isEmpty()) {
                        chunks[0]++;
                        text.append(chunk);
                        onChunk.accept(chunk);
                    }
                }
                case A2a.Message message -> text.append(message.text());
            }
        });
        return new Report(taskId[0], text.toString(), state[0], progress, chunks[0]);
    }

    /** Starts a report with a non-blocking {@code message/send}; returns the task id right away. */
    public String start(String topic) {
        A2a.Task task = a2a.sendMessage(card(), new A2a.MessageSendParams(A2a.Message.user(topic, contextId),
                A2a.MessageSendConfiguration.nonBlocking(), null));
        return task.id();
    }

    /** Current state of a task ({@code tasks/get}), without its message history. */
    public A2a.Task status(String taskId) {
        return a2a.getTask(card(), taskId, 0);
    }

    public A2a.Task cancel(String taskId) {
        return a2a.cancelTask(card(), taskId);
    }

    /** Polls {@code tasks/get} until the task is terminal or {@code timeout} passes. */
    public A2a.Task await(String taskId, Duration pollInterval, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        A2a.Task task = status(taskId);
        while (!task.status().state().isTerminal() && System.nanoTime() < deadline) {
            Thread.sleep(pollInterval);
            task = status(taskId);
        }
        return task;
    }
}
