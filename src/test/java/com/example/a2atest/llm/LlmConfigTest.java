package com.example.a2atest.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Only checks how the configuration is read; no model is contacted. */
class LlmConfigTest {

    @Test
    void defaultsToThePlaceholder() {
        assertEquals(LlmConfig.Provider.PLACEHOLDER, LlmConfig.from(Map.of()).provider());
    }

    @Test
    void ollamaHasLocalDefaults() {
        LlmConfig config = LlmConfig.from(Map.of("A2A_LLM", "ollama"));
        assertEquals("llama3.2", config.modelName());
        assertEquals("http://localhost:11434", config.baseUrl());
    }

    @Test
    void anthropicNeedsAnApiKey() {
        assertThrows(IllegalStateException.class, () -> LlmConfig.from(Map.of("A2A_LLM", "anthropic")));
        LlmConfig config = LlmConfig.from(Map.of("A2A_LLM", "Anthropic", "ANTHROPIC_API_KEY", "k",
                "A2A_LLM_MODEL", "claude-haiku-5-5"));
        assertEquals("claude-haiku-5-5", config.modelName());
    }
}
