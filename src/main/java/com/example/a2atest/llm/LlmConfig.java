package com.example.a2atest.llm;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chooses the LLM behind the agents from the environment, so the same build runs offline (the default
 * deterministic placeholders) or against a real model:
 * <pre>
 * A2A_LLM           placeholder (default) | ollama | anthropic
 * A2A_LLM_MODEL     model name; default llama3.2 (ollama) or claude-sonnet-5-5 (anthropic)
 * A2A_LLM_BASE_URL  ollama: default http://localhost:11434; anthropic: optional
 * ANTHROPIC_API_KEY required for anthropic
 * </pre>
 */
public record LlmConfig(Provider provider, String modelName, String baseUrl, String apiKey) {

    public enum Provider { PLACEHOLDER, OLLAMA, ANTHROPIC }

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    /** No real LLM: every agent uses its deterministic placeholder rule (used by the tests). */
    public static LlmConfig placeholder() {
        return new LlmConfig(Provider.PLACEHOLDER, "rule-based", null, null);
    }

    public static LlmConfig fromEnv() {
        return from(System.getenv());
    }

    static LlmConfig from(Map<String, String> env) {
        Provider provider = Provider.valueOf(env.getOrDefault("A2A_LLM", "placeholder").trim().toUpperCase(Locale.ROOT));
        String model = env.get("A2A_LLM_MODEL");
        String baseUrl = env.get("A2A_LLM_BASE_URL");
        return switch (provider) {
            case PLACEHOLDER -> new LlmConfig(provider, "rule-based", null, null);
            case OLLAMA -> new LlmConfig(provider, model != null ? model : "llama3.2",
                    baseUrl != null ? baseUrl : "http://localhost:11434", null);
            case ANTHROPIC -> {
                String key = env.get("ANTHROPIC_API_KEY");
                if (key == null || key.isBlank()) {
                    throw new IllegalStateException("A2A_LLM=anthropic needs ANTHROPIC_API_KEY");
                }
                yield new LlmConfig(provider, model != null ? model : "claude-sonnet-5-5", baseUrl, key);
            }
        };
    }

    /** The configured chat model, or {@code placeholder} when no real LLM is configured. */
    public ChatModel chatModel(RuleBasedChatModel placeholder, List<ChatModelListener> listeners) {
        return switch (provider) {
            case PLACEHOLDER -> placeholder.withListeners(listeners);
            case OLLAMA -> (ChatModel) OllamaChatModel.builder().baseUrl(baseUrl).modelName(modelName)
                    .timeout(TIMEOUT).listeners(listeners).build();
            case ANTHROPIC -> {
                AnthropicChatModel.AnthropicChatModelBuilder builder = AnthropicChatModel.builder().apiKey(apiKey)
                        .modelName(modelName).timeout(TIMEOUT).listeners(listeners);
                yield (baseUrl != null ? builder.baseUrl(baseUrl) : builder).build();
            }
        };
    }

    /** The configured streaming chat model, or {@code placeholder} when no real LLM is configured. */
    public StreamingChatModel streamingChatModel(RuleBasedStreamingChatModel placeholder,
                                                 List<ChatModelListener> listeners) {
        return switch (provider) {
            case PLACEHOLDER -> placeholder.withListeners(listeners);
            case OLLAMA -> (StreamingChatModel) OllamaStreamingChatModel.builder().baseUrl(baseUrl)
                    .modelName(modelName).timeout(TIMEOUT).listeners(listeners).build();
            case ANTHROPIC -> {
                AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder builder = AnthropicStreamingChatModel
                        .builder().apiKey(apiKey).modelName(modelName).timeout(TIMEOUT).listeners(listeners);
                yield (baseUrl != null ? builder.baseUrl(baseUrl) : builder).build();
            }
        };
    }
}
