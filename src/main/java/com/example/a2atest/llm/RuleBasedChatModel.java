package com.example.a2atest.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * Stand-in for a real LLM: a LangChain4j {@link ChatModel} whose answer is computed by a deterministic rule
 * over the whole {@link ChatRequest}. Unlike the simple {@code PlaceholderChatModel} the rule sees the system
 * message, the memory, the tool specifications and tool results, so it can return tool calls (see
 * {@link Rules#callTool}) and exercise the agents' tool, RAG and guardrail paths without an LLM. Token usage is
 * reported as word counts and listeners are notified like for a real model.
 */
public final class RuleBasedChatModel implements ChatModel {

    private final Function<ChatRequest, AiMessage> rule;
    private final List<ChatModelListener> listeners;
    private final Duration latency;

    public RuleBasedChatModel(Function<ChatRequest, AiMessage> rule) {
        this(rule, List.of(), Duration.ZERO);
    }

    private RuleBasedChatModel(Function<ChatRequest, AiMessage> rule, List<ChatModelListener> listeners,
                               Duration latency) {
        this.rule = rule;
        this.listeners = List.copyOf(listeners);
        this.latency = latency;
    }

    public RuleBasedChatModel withListeners(List<ChatModelListener> listeners) {
        return new RuleBasedChatModel(rule, listeners, latency);
    }

    /** Simulates a slow model: every call sleeps this long first (used to test timeouts). */
    public RuleBasedChatModel withLatency(Duration latency) {
        return new RuleBasedChatModel(rule, listeners, latency);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        Rules.sleep(latency);
        AiMessage answer = rule.apply(request);
        return ChatResponse.builder()
                .aiMessage(answer)
                .modelName("rule-based")
                .tokenUsage(Rules.tokenUsage(request, answer.text()))
                .finishReason(answer.hasToolExecutionRequests() ? FinishReason.TOOL_EXECUTION : FinishReason.STOP)
                .build();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return listeners;
    }
}
