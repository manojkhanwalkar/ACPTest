package com.example.a2atest.a2a;

/** An agent that can be hosted by an {@link A2aServer} and invoked with A2A {@code message/send} calls. */
public interface A2aAgent {

    /** The agent's card; {@code url} is the JSON-RPC endpoint the server is reachable at. */
    A2a.AgentCard card(String url);

    /**
     * Handles one incoming message synchronously.
     *
     * @param message   the client's message
     * @param contextId the A2A context the message belongs to (lets the agent keep per-conversation memory)
     * @return the text of the agent's reply
     */
    String reply(A2a.Message message, String contextId);
}
