package com.example.acptest.agent;

import com.example.acptest.acp.Acp;
import com.example.acptest.acp.AcpClient;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The requesting agent. Its LangChain4j assistant turns a task into a request message, which is sent to
 * Agent2 as an ACP run; the text of the run's output is Agent2's response.
 */
public final class Agent1 {

    public static final String NAME = "agent1";

    private final Assistant assistant;
    private final AcpClient acp;
    private final String sessionId = UUID.randomUUID().toString();

    /** Agent1 without an LLM: uses the task verbatim as the request. */
    public Agent1(AcpClient acp) {
        this(acp, new PlaceholderChatModel(task -> task));
    }

    public Agent1(AcpClient acp, ChatModel model) {
        this.acp = acp;
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }

    /** Result of one request/response exchange. */
    public record Exchange(String request, Acp.Run run) {

        public String response() {
            return run.output().stream().map(Acp.Message::text).collect(Collectors.joining("\n"));
        }
    }

    /** Composes a request for the task, sends it to Agent2 over ACP and returns the exchange. */
    public Exchange askAgent2(String task) {
        Acp.AgentManifest agent2 = acp.agent(Agent2.NAME);
        String request = assistant.chat(sessionId, task);
        Acp.Run run = acp.runSync(agent2.name(), sessionId, List.of(Acp.Message.user(request)));
        if (run.status() != Acp.RunStatus.COMPLETED) {
            throw new IllegalStateException("Run " + run.runId() + " ended with status " + run.status()
                    + (run.error() != null ? ": " + run.error().message() : ""));
        }
        return new Exchange(request, run);
    }
}
