package com.example.a2atest.agents.support;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailResult;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailResult;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** LangChain4j input and output guardrails of the {@link SupportAgent}. */
public final class Guardrails {

    private Guardrails() {
    }

    /** Refuses messages longer than {@code maxChars} before they reach the LLM. */
    public record MaxLength(int maxChars) implements InputGuardrail {

        @Override
        public InputGuardrailResult validate(UserMessage userMessage) {
            int length = userMessage.singleText().length();
            return length > maxChars
                    ? fatal("Message too long (" + length + " characters, at most " + maxChars + ")")
                    : success();
        }
    }

    /** Refuses common prompt-injection phrasings. A heuristic, not a complete defence. */
    public static final class PromptInjection implements InputGuardrail {

        private static final List<String> PHRASES = List.of("ignore previous instructions",
                "ignore all previous instructions", "ignore the above", "disregard your instructions",
                "reveal your system prompt", "you are now");

        @Override
        public InputGuardrailResult validate(UserMessage userMessage) {
            String text = userMessage.singleText().toLowerCase(Locale.ROOT);
            return PHRASES.stream().anyMatch(text::contains)
                    ? fatal("Message looks like a prompt-injection attempt")
                    : success();
        }
    }

    /** Masks e-mail addresses and card-like numbers in the LLM's answer before it leaves the agent. */
    public static final class PiiRedaction implements OutputGuardrail {

        private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
        private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){12,15}\\d\\b");

        @Override
        public OutputGuardrailResult validate(AiMessage responseFromLLM) {
            String text = responseFromLLM.text();
            String redacted = CARD.matcher(EMAIL.matcher(text).replaceAll("[email redacted]"))
                    .replaceAll("[card number redacted]");
            return redacted.equals(text) ? success() : successWith(redacted);
        }
    }
}
