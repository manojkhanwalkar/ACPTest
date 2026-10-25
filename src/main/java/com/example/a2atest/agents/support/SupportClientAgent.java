package com.example.a2atest.agents.support;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aClient;
import com.example.a2atest.agents.RemoteAgent;

import java.net.URI;

/**
 * Client of the {@link SupportAgent}. Holds the API key the agent's card asks for and keeps one support
 * conversation ({@code contextId}) across calls.
 */
public final class SupportClientAgent extends RemoteAgent {

    /** The agent's reply; {@code state} is {@code rejected} when a guardrail blocked the message. */
    public record Reply(A2a.TaskState state, String text) {
    }

    public SupportClientAgent(URI agentBaseUri, String apiKey) {
        super(new A2aClient(agentBaseUri, apiKey));
    }

    public Reply say(String message) {
        A2a.Task task = send(message);
        return new Reply(task.status().state(), task.statusText());
    }
}
