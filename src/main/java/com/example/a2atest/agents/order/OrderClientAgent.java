package com.example.a2atest.agents.order;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.agents.RemoteAgent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Client of the {@link OrderAgent}. Sends a question and, while the task is {@code input-required}, answers
 * the agent's clarifying question with {@code answerer} and continues the same task.
 */
public final class OrderClientAgent extends RemoteAgent {

    private static final int MAX_CLARIFICATIONS = 3;

    /**
     * Outcome of a question.
     *
     * @param text      the agent's answer, or its open question when {@code needsInput}
     * @param taskId    the A2A task
     * @param questions clarifying questions the agent asked along the way
     * @param needsInput true when the agent still needed input and the task was canceled
     */
    public record Answer(String text, String taskId, List<String> questions, boolean needsInput) {
    }

    public OrderClientAgent(A2aClient a2a) {
        super(a2a);
    }

    /** Asks without being able to answer follow-up questions: an open question is returned with needsInput. */
    public Answer ask(String question) {
        return ask(question, q -> null);
    }

    /**
     * Asks a question; {@code answerer} gets each clarifying question of the agent and returns the reply, or
     * null to give up (the task is then canceled).
     */
    public Answer ask(String question, UnaryOperator<String> answerer) {
        A2a.Task task = send(question);
        List<String> questions = new ArrayList<>();
        while (task.status().state() == A2a.TaskState.INPUT_REQUIRED) {
            String agentQuestion = task.statusText();
            questions.add(agentQuestion);
            String reply = questions.size() <= MAX_CLARIFICATIONS ? answerer.apply(agentQuestion) : null;
            if (reply == null) {
                a2a.cancelTask(card(), task.id());
                return new Answer(agentQuestion, task.id(), questions, true);
            }
            // continuing the task: same taskId and contextId
            task = a2a.sendMessage(card(), A2a.Message.user(List.of(A2a.Part.text(reply)), contextId, task.id(), null));
        }
        if (task.status().state() != A2a.TaskState.COMPLETED) {
            throw new IllegalStateException("Task " + task.id() + " ended " + task.status().state().wire() + ": "
                    + task.statusText());
        }
        return new Answer(task.statusText(), task.id(), questions, false);
    }
}
