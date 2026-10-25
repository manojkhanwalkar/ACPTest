package com.example.a2atest.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * Streaming counterpart of {@link RuleBasedChatModel}: the rule's answer is emitted word by word as partial
 * responses, with an optional delay per token so streaming and cancellation can be observed.
 */
public final class RuleBasedStreamingChatModel implements StreamingChatModel {

    private final Function<ChatRequest, String> rule;
    private final Duration tokenDelay;
    private final List<ChatModelListener> listeners;

    public RuleBasedStreamingChatModel(Function<ChatRequest, String> rule, Duration tokenDelay) {
        this(rule, tokenDelay, List.of());
    }

    private RuleBasedStreamingChatModel(Function<ChatRequest, String> rule, Duration tokenDelay,
                                        List<ChatModelListener> listeners) {
        this.rule = rule;
        this.tokenDelay = tokenDelay;
        this.listeners = List.copyOf(listeners);
    }

    public RuleBasedStreamingChatModel withListeners(List<ChatModelListener> listeners) {
        return new RuleBasedStreamingChatModel(rule, tokenDelay, listeners);
    }

    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        String answer = rule.apply(request);
        try {
            // keep the whitespace with the word so the chunks concatenate back to the answer
            for (String token : answer.split("(?<=\\s)")) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException();
                }
                Rules.sleep(tokenDelay);
                handler.onPartialResponse(token);
            }
        } catch (InterruptedException | RuntimeException e) {
            handler.onError(e);
            return;
        }
        handler.onCompleteResponse(ChatResponse.builder()
                .aiMessage(AiMessage.from(answer))
                .modelName("rule-based")
                .tokenUsage(Rules.tokenUsage(request, answer))
                .finishReason(FinishReason.STOP)
                .build());
    }

    @Override
    public List<ChatModelListener> listeners() {
        return listeners;
    }
}
