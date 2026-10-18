package com.example.acptest.acp;

import java.util.List;

/** An agent that can be hosted by an {@link AcpServer} and invoked through ACP runs. */
public interface AcpAgent {

    Acp.AgentManifest manifest();

    /**
     * Handles one run synchronously.
     *
     * @param input     the run's input messages
     * @param sessionId the ACP session the run belongs to (lets the agent keep per-conversation memory)
     * @return the agent's output messages
     */
    List<Acp.Message> run(List<Acp.Message> input, String sessionId);
}
