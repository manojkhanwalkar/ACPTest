package com.example.acptest.agent;

import com.example.a2atest.memory.ConversationMemory;
import com.example.acptest.acp.Acp;
import com.example.acptest.acp.AcpAgent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The responding agent. It is hosted on an ACP server; each incoming run is passed to its LangChain4j
 * assistant and the assistant's answer is returned as the run's output message.
 */
public final class Agent2 implements AcpAgent {

    public static final String NAME = "agent2";

    /** Conversations kept at once; the least recently used one is dropped beyond this. */
    public static final int MAX_SESSIONS = 1000;

    private final Assistant assistant;
    // bounds the chat memory so callers can't grow it with new session ids
    private final ConversationMemory memory;

    /** Agent2 without an LLM: replies with a fixed acknowledgement of the request. */
    public Agent2() {
        this(new PlaceholderChatModel(request -> "Hello Agent1, Agent2 received your request: \"" + request + "\""));
    }

    public Agent2(ChatModel model) {
        this(model, ConversationMemory.inMemory(20, MAX_SESSIONS, Duration.ofMinutes(30)));
    }

    public Agent2(ChatModel model, ConversationMemory memory) {
        this.memory = memory;
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemoryProvider(memory)
                .build();
        memory.bind(assistant);
    }

    @Override
    public Acp.AgentManifest manifest() {
        return new Acp.AgentManifest(NAME, "Answers requests sent by other agents",
                List.of(Acp.TEXT_PLAIN), List.of(Acp.TEXT_PLAIN));
    }

    @Override
    public List<Acp.Message> run(List<Acp.Message> input, String sessionId) {
        String request = input.stream().map(Acp.Message::text).collect(Collectors.joining("\n"));
        return memory.use(sessionId, () -> List.of(Acp.Message.agent(NAME, assistant.chat(sessionId, request))));
    }

    /** Number of conversations currently held in memory. */
    public int activeSessions() {
        return memory.activeConversations();
    }
}
