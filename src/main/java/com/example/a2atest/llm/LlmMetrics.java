package com.example.a2atest.llm;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * LangChain4j {@link ChatModelListener} that counts LLM calls, errors, tokens and latency for one agent and
 * logs each call with the A2A task and context ids the server put in the MDC.
 */
public final class LlmMetrics implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(LlmMetrics.class);
    private static final String START = "a2a.start";

    private final String agent;
    private final LongAdder calls = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder inputTokens = new LongAdder();
    private final LongAdder outputTokens = new LongAdder();
    private final LongAdder latencyMillis = new LongAdder();

    public LlmMetrics(String agent) {
        this.agent = agent;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        context.attributes().put(START, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        long millis = elapsedMillis(context.attributes());
        calls.increment();
        latencyMillis.add(millis);
        TokenUsage usage = context.chatResponse().tokenUsage();
        int in = usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int out = usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        inputTokens.add(in);
        outputTokens.add(out);
        log.info("LLM call by {} (task {}, context {}): {} ms, {} in / {} out tokens", agent, MDC.get("taskId"),
                MDC.get("contextId"), millis, in, out);
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        errors.increment();
        log.warn("LLM call by {} (task {}) failed: {}", agent, MDC.get("taskId"), context.error().getMessage());
    }

    public long calls() {
        return calls.sum();
    }

    /** Counters for {@code GET /metrics}. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("llmCalls", calls.sum());
        m.put("llmErrors", errors.sum());
        m.put("inputTokens", inputTokens.sum());
        m.put("outputTokens", outputTokens.sum());
        m.put("avgLatencyMs", calls.sum() == 0 ? 0 : latencyMillis.sum() / calls.sum());
        return m;
    }

    private static long elapsedMillis(Map<Object, Object> attributes) {
        Object start = attributes.get(START);
        return start instanceof Long nanos ? (System.nanoTime() - nanos) / 1_000_000 : 0;
    }
}
