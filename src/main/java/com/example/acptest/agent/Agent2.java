package com.example.acptest.agent;

import com.example.acptest.acp.Acp;
import com.example.acptest.acp.AcpAgent;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The responding agent. It is hosted on an ACP server; each incoming run is passed to its LangChain4j
 * assistant and the assistant's answer is returned as the run's output message.
 */
public final class Agent2 implements AcpAgent {

    public static final String NAME = "agent2";

    private final Assistant assistant;

    /** Agent2 without an LLM: replies with a fixed acknowledgement of the request. */
    public Agent2() {
        this(new PlaceholderChatModel(request -> "Hello Agent1, Agent2 received your request: \"" + request + "\""));
    }

    public Agent2(ChatModel model) {
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemoryProvider(sessionId -> MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }

    @Override
    public Acp.AgentManifest manifest() {
        return new Acp.AgentManifest(NAME, "Answers requests sent by other agents",
                List.of(Acp.TEXT_PLAIN), List.of(Acp.TEXT_PLAIN));
    }

    @Override
    public List<Acp.Message> run(List<Acp.Message> input, String sessionId) {
        String request = input.stream().map(Acp.Message::text).collect(Collectors.joining("\n"));
        return List.of(Acp.Message.agent(NAME, assistant.chat(sessionId, request)));
    }
}
