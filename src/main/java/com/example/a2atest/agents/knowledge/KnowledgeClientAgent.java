package com.example.a2atest.agents.knowledge;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.agents.RemoteAgent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/** Client of the {@link KnowledgeAgent}: returns the answer together with the documents it is based on. */
public final class KnowledgeClientAgent extends RemoteAgent {

    public record Answer(String text, List<String> sources) {
    }

    public KnowledgeClientAgent(A2aClient a2a) {
        super(a2a);
    }

    public Answer ask(String question) {
        return ask(question, contextId);
    }

    /** Asks in the conversation {@code contextId}. */
    public Answer ask(String question, String contextId) {
        A2a.Task task = send(question, contextId);
        if (task.status().state() != A2a.TaskState.COMPLETED) {
            throw new IllegalStateException("Task " + task.id() + " ended " + task.status().state().wire());
        }
        JsonNode data = task.status().message().data();
        try {
            List<String> sources = data == null ? List.of()
                    : A2a.JSON.treeToValue(data, KnowledgeAgent.Sources.class).sources();
            return new Answer(task.statusText(), sources);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unexpected data in task " + task.id(), e);
        }
    }
}
