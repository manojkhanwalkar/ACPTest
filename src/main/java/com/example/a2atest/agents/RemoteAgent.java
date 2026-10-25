package com.example.a2atest.agents;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aClient;

import java.util.UUID;

/**
 * Base of the client agents: wraps an {@link A2aClient} for one remote agent, discovers that agent's card on
 * first use and keeps one A2A {@code contextId}, so the remote agent sees all calls of this client as one
 * conversation.
 */
public abstract class RemoteAgent {

    protected final A2aClient a2a;
    protected final String contextId = UUID.randomUUID().toString();
    private A2a.AgentCard card;

    protected RemoteAgent(A2aClient a2a) {
        this.a2a = a2a;
    }

    /** The remote agent's card, fetched from its well-known URL once. */
    public synchronized A2a.AgentCard card() {
        if (card == null) {
            card = a2a.agentCard();
        }
        return card;
    }

    public String contextId() {
        return contextId;
    }

    protected A2a.Task send(String text) {
        return a2a.sendMessage(card(), A2a.Message.user(text, contextId));
    }
}
