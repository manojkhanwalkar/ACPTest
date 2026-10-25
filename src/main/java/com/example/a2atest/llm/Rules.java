package com.example.a2atest.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Helpers for writing the deterministic rules of {@link RuleBasedChatModel}. */
public final class Rules {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Rules() {
    }

    /** Text of the latest user message (including anything RAG appended to it), or "". */
    public static String lastUserText(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                return user.singleText();
            }
        }
        return "";
    }

    /** Number of user messages in the request, i.e. the turn number within the remembered conversation. */
    public static int userTurns(ChatRequest request) {
        return (int) request.messages().stream().filter(UserMessage.class::isInstance).count();
    }

    public static String systemText(ChatRequest request) {
        for (ChatMessage message : request.messages()) {
            if (message instanceof SystemMessage system) {
                return system.text();
            }
        }
        return "";
    }

    /** Results of the tools executed since the latest user message (empty when the model must still act). */
    public static List<ToolExecutionResultMessage> toolResultsSinceLastUser(ChatRequest request) {
        List<ToolExecutionResultMessage> results = new ArrayList<>();
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0 && !(messages.get(i) instanceof UserMessage); i--) {
            if (messages.get(i) instanceof ToolExecutionResultMessage result) {
                results.addFirst(result);
            }
        }
        return results;
    }

    /** True when the model may call {@code toolName} in this request. */
    public static boolean hasTool(ChatRequest request, String toolName) {
        return request.toolSpecifications() != null
                && request.toolSpecifications().stream().anyMatch(t -> t.name().equals(toolName));
    }

    /** An AI message asking LangChain4j to execute one tool. */
    public static AiMessage callTool(String name, Map<String, ?> arguments) {
        return callTools(List.of(Map.entry(name, arguments)));
    }

    /** An AI message asking LangChain4j to execute several tools in one step. */
    public static AiMessage callTools(List<Map.Entry<String, ? extends Map<String, ?>>> calls) {
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (Map.Entry<String, ? extends Map<String, ?>> call : calls) {
            try {
                requests.add(ToolExecutionRequest.builder()
                        .id(UUID.randomUUID().toString())
                        .name(call.getKey())
                        .arguments(JSON.writeValueAsString(call.getValue()))
                        .build());
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return AiMessage.from(requests);
    }

    static TokenUsage tokenUsage(ChatRequest request, String answer) {
        int input = 0;
        for (ChatMessage message : request.messages()) {
            input += words(message.toString());
        }
        return new TokenUsage(input, words(answer));
    }

    private static int words(String text) {
        return text == null || text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    static void sleep(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
