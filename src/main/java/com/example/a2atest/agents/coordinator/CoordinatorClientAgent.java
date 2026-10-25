package com.example.a2atest.agents.coordinator;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.agents.RemoteAgent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Client of the {@link CoordinatorAgent}: returns the combined answer and which agents were consulted. */
public final class CoordinatorClientAgent extends RemoteAgent {

    public record Answer(String text, List<CoordinatorAgent.Delegation> delegations) {
    }

    public CoordinatorClientAgent(A2aClient a2a) {
        super(a2a);
    }

    public Answer ask(String question) {
        A2a.Task task = send(question);
        if (task.status().state() != A2a.TaskState.COMPLETED) {
            throw new IllegalStateException("Task " + task.id() + " ended " + task.status().state().wire() + ": "
                    + task.statusText());
        }
        JsonNode data = task.status().message().data();
        try {
            List<CoordinatorAgent.Delegation> delegations = data == null ? List.of()
                    : A2a.JSON.treeToValue(data, CoordinatorAgent.Delegations.class).delegations();
            return new Answer(task.statusText(), delegations);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unexpected data in task " + task.id(), e);
        }
    }
}
