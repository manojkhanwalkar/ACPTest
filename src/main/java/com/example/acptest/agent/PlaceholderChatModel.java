package com.example.acptest.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Stand-in for a real LLM: a LangChain4j {@link ChatModel} that answers the latest user message with a
 * deterministic rule. Agents are built on the {@code ChatModel} interface, so connecting an LLM later
 * means passing e.g. an {@code OllamaChatModel} instead of this class; nothing else changes.
 */
public final class PlaceholderChatModel implements ChatModel {

    private final UnaryOperator<String> rule;

    public PlaceholderChatModel(UnaryOperator<String> rule) {
        this.rule = rule;
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        String latest = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                latest = user.singleText();
                break;
            }
        }
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(rule.apply(latest)))
                .modelName("placeholder")
                .finishReason(FinishReason.STOP)
                .build();
    }
}
