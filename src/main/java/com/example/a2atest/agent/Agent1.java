package com.example.a2atest.agent;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aClient;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;

import java.util.UUID;

/**
 * The requesting agent. It discovers Agent2 through its agent card, its LangChain4j assistant turns a task
 * into a request message, and the message is sent with A2A {@code message/send}; the agent message in the
 * returned task's status is Agent2's response.
 */
public final class Agent1 {

    public static final String NAME = "agent1";

    private final Assistant assistant;
    private final A2aClient a2a;
    private final String contextId = UUID.randomUUID().toString();

    /** Agent1 without an LLM: uses the task verbatim as the request. */
    public Agent1(A2aClient a2a) {
        this(a2a, new PlaceholderChatModel(task -> task));
    }

    public Agent1(A2aClient a2a, ChatModel model) {
        this.a2a = a2a;
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .chatMemoryProvider(id -> MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }

    /** Result of one request/response exchange. */
    public record Exchange(String request, A2a.Task task) {

        public String response() {
            return task.status().message().text();
        }
    }

    /** Composes a request for the task, sends it to Agent2 over A2A and returns the exchange. */
    public Exchange askAgent2(String task) {
        A2a.AgentCard agent2 = a2a.agentCard();
        String request = assistant.chat(contextId, task);
        A2a.Task result = a2a.sendMessage(agent2, A2a.Message.user(request, contextId));
        if (result.status().state() != A2a.TaskState.COMPLETED) {
            throw new IllegalStateException("Task " + result.id() + " ended in state " + result.status().state()
                    + (result.status().message() != null ? ": " + result.status().message().text() : ""));
        }
        return new Exchange(request, result);
    }
}
