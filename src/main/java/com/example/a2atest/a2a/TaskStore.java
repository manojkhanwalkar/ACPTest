package com.example.a2atest.a2a;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * In-memory tasks of one {@link A2aServer}. It is bounded: once more than {@code maxTasks} tasks exist the
 * oldest is dropped, so a long-running server doesn't grow without limit.
 */
final class TaskStore {

    private final Map<String, TaskRecord> tasks;

    TaskStore(int maxTasks) {
        tasks = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, TaskRecord> eldest) {
                return size() > maxTasks;
            }
        };
    }

    /** A new task owned by {@code owner}, the principal that may read, continue and cancel it. */
    synchronized TaskRecord create(String contextId, String owner) {
        TaskRecord task = new TaskRecord(UUID.randomUUID().toString(), contextId, owner);
        tasks.put(task.id(), task);
        return task;
    }

    /** The task, or null if it doesn't exist or belongs to another principal (callers can't tell which). */
    synchronized TaskRecord get(String id, String principal) {
        TaskRecord task = id == null ? null : tasks.get(id);
        return task != null && task.owner().equals(principal) ? task : null;
    }

    synchronized Map<String, Long> countByState() {
        Map<A2a.TaskState, Long> counts = new EnumMap<>(A2a.TaskState.class);
        for (TaskRecord task : tasks.values()) {
            counts.merge(task.state(), 1L, Long::sum);
        }
        Map<String, Long> byWireName = new LinkedHashMap<>();
        counts.forEach((state, n) -> byWireName.put(state.wire(), n));
        return byWireName;
    }

    /** Mutable state of one task. All mutations are synchronized so events are published in order. */
    static final class TaskRecord {

        private final String id;
        private final String contextId;
        private final String owner;
        private final List<A2a.Message> history = new ArrayList<>();
        private final Map<String, A2a.Artifact> artifacts = new LinkedHashMap<>();
        private final List<Consumer<A2a.StreamEvent>> listeners = new CopyOnWriteArrayList<>();
        private A2a.TaskStatus status = new A2a.TaskStatus(A2a.TaskState.SUBMITTED, null, Instant.now());
        private CompletableFuture<Void> settled = new CompletableFuture<>();
        private Future<?> execution;

        private TaskRecord(String id, String contextId, String owner) {
            this.id = id;
            this.contextId = contextId;
            this.owner = owner;
        }

        String id() {
            return id;
        }

        String contextId() {
            return contextId;
        }

        String owner() {
            return owner;
        }

        synchronized A2a.TaskState state() {
            return status.state();
        }

        synchronized List<A2a.Message> history() {
            return List.copyOf(history);
        }

        /** Completes when the current turn reaches a terminal or interrupted state. */
        synchronized CompletableFuture<Void> settled() {
            return settled;
        }

        synchronized void execution(Future<?> execution) {
            this.execution = execution;
        }

        /**
         * Starts a turn with the client's message: back to {@code submitted}, with a fresh {@link #settled()}.
         * A continuation is accepted only while the task waits for input; checking and starting in one
         * synchronized step means two concurrent continuations can't both be accepted.
         */
        synchronized boolean beginTurn(A2a.Message message, boolean continuation) {
            if (continuation && !status.state().isInterrupted()) {
                return false;
            }
            history.add(message);
            status = new A2a.TaskStatus(A2a.TaskState.SUBMITTED, null, Instant.now());
            settled = new CompletableFuture<>();
            return true;
        }

        /** Sets the status unless the task is already terminal; returns whether it changed. */
        synchronized boolean updateStatus(A2a.TaskState state, A2a.Message message) {
            if (status.state().isTerminal()) {
                return false;
            }
            status = new A2a.TaskStatus(state, message, Instant.now());
            boolean settles = state.isTerminal() || state.isInterrupted();
            if (settles && message != null) {
                history.add(message);
            }
            publish(new A2a.TaskStatusUpdateEvent(id, contextId, status, settles));
            if (settles) {
                settled.complete(null);
            }
            return true;
        }

        /** Adds an artifact, or with {@code append} adds the chunk's text to the artifact with the same id. */
        synchronized void addArtifact(A2a.Artifact chunk, boolean append, boolean lastChunk) {
            if (status.state().isTerminal()) {
                return;
            }
            A2a.Artifact existing = artifacts.get(chunk.artifactId());
            boolean appending = append && existing != null;
            artifacts.put(chunk.artifactId(), appending
                    ? new A2a.Artifact(existing.artifactId(), existing.name(), existing.description(),
                    List.of(A2a.Part.text(existing.text() + chunk.text())))
                    : chunk);
            publish(new A2a.TaskArtifactUpdateEvent(id, contextId, chunk, appending, lastChunk));
        }

        /** Cancels a task that isn't terminal yet and interrupts the agent working on it. */
        boolean cancel() {
            Future<?> running;
            synchronized (this) {
                if (!updateStatus(A2a.TaskState.CANCELED, A2a.Message.agent("Task canceled", contextId, id))) {
                    return false;
                }
                running = execution;
            }
            if (running != null) {
                running.cancel(true);
            }
            return true;
        }

        /** Fails a turn that took longer than {@code timeout} and interrupts the agent. */
        boolean cancelForTimeout(Duration timeout) {
            Future<?> running;
            synchronized (this) {
                if (status.state().isInterrupted() || !updateStatus(A2a.TaskState.FAILED,
                        A2a.Message.agent("Timed out after " + timeout.toMillis() + " ms", contextId, id))) {
                    return false;
                }
                running = execution;
            }
            if (running != null) {
                running.cancel(true);
            }
            return true;
        }

        synchronized A2a.Task snapshot(Integer historyLength) {
            return new A2a.Task("task", id, contextId, status, A2a.tail(history, historyLength),
                    artifacts.isEmpty() ? null : List.copyOf(artifacts.values()));
        }

        void subscribe(Consumer<A2a.StreamEvent> listener) {
            listeners.add(listener);
        }

        void unsubscribe(Consumer<A2a.StreamEvent> listener) {
            listeners.remove(listener);
        }

        private void publish(A2a.StreamEvent event) {
            for (Consumer<A2a.StreamEvent> listener : listeners) {
                listener.accept(event);
            }
        }
    }
}
