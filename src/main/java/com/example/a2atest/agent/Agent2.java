package com.example.a2atest.agent;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aAgent;
import com.example.a2atest.memory.ConversationMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.time.Duration;
import java.util.List;

/**
 * The responding agent. It is hosted on an A2A server; each incoming {@code message/send} is passed to its
 * LangChain4j assistant and the assistant's answer becomes the agent message of the completed task.
 */
public final class Agent2 implements A2aAgent {

    public static final String NAME = "agent2";

    private final Assistant assistant;
    // bounded: at most 1000 conversations of 20 messages, dropped after 30 idle minutes
    private final ConversationMemory memory = ConversationMemory.inMemory(20, 1000, Duration.ofMinutes(30));

    /** Agent2 without an LLM: replies with a fixed acknowledgement of the request. */
    public Agent2() {
        this(new PlaceholderChatModel(request -> "Hello Agent1, Agent2 received your request: \"" + request + "\""));
    }

    public Agent2(ChatModel model) {
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemoryProvider(memory)
                .build();
        memory.bind(assistant);
    }

    @Override
    public A2a.AgentCard card(String url) {
        return new A2a.AgentCard(A2a.PROTOCOL_VERSION, NAME, "Answers requests sent by other agents", url,
                "JSONRPC", "1.0.0", new A2a.AgentCapabilities(false, false),
                List.of(A2a.TEXT_PLAIN), List.of(A2a.TEXT_PLAIN),
                List.of(new A2a.AgentSkill("answer", "Answer requests", "Replies to a plain-text request",
                        List.of("qa"))));
    }

    @Override
    public String reply(A2a.Message message, String contextId) {
        memory.touch(contextId);
        return assistant.chat(contextId, message.text());
    }
}
