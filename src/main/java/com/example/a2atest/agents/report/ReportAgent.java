package com.example.a2atest.agents.report;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aAgent;
import com.example.a2atest.a2a.RequestContext;
import com.example.a2atest.a2a.TaskUpdater;
import com.example.a2atest.llm.LlmConfig;
import com.example.a2atest.llm.LlmMetrics;
import com.example.a2atest.llm.RuleBasedStreamingChatModel;
import com.example.a2atest.llm.Rules;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Long-running report writer. Shows <b>streaming</b> and the <b>task lifecycle</b>:
 * <ul>
 *     <li>its card sets {@code capabilities.streaming}; with {@code message/stream} the client receives the
 *     progress status updates and the report as {@code artifact-update} chunks while the LLM streams tokens
 *     (LangChain4j {@link TokenStream} over a {@code StreamingChatModel})</li>
 *     <li>with a non-blocking {@code message/send} the client gets the task id at once, polls it with
 *     {@code tasks/get} and can stop it with {@code tasks/cancel}; the agent checks for cancellation between
 *     tokens</li>
 * </ul>
 */
public final class ReportAgent implements A2aAgent {

    public static final String NAME = "report-agent";
    public static final String ARTIFACT = "report";

    interface ReportWriter {

        @SystemMessage("Write a short structured report (title, findings, recommendation) on the topic.")
        TokenStream write(@UserMessage String topic);
    }

    private final ReportWriter writer;
    private final LlmMetrics metrics = new LlmMetrics(NAME);

    public ReportAgent() {
        this(LlmConfig.fromEnv(), Duration.ofMillis(40));
    }

    /** {@code placeholderTokenDelay} slows the placeholder model down so streaming and cancel can be seen. */
    public ReportAgent(LlmConfig llm, Duration placeholderTokenDelay) {
        writer = AiServices.builder(ReportWriter.class)
                .streamingChatModel(llm.streamingChatModel(placeholderModel(placeholderTokenDelay), List.of(metrics)))
                .build();
    }

    @Override
    public A2a.AgentCard card(String url) {
        return A2a.AgentCard.of(NAME, "Writes reports, streaming them as they are written; long tasks can be "
                        + "polled and canceled", url, true, List.of(A2a.TEXT_PLAIN), List.of(
                new A2a.AgentSkill("report", "Write report", "A short report on a topic, streamed as an artifact",
                        List.of("streaming", "long-running"), List.of("Q3 sales of audio products"), null, null)));
    }

    @Override
    public void execute(RequestContext request, TaskUpdater task) {
        String topic = request.message().text();
        task.working("Researching \"" + topic + "\"");
        if (task.isCanceled()) {
            return;
        }
        task.working("Writing the report");

        String artifactId = UUID.randomUUID().toString();
        AtomicInteger chunks = new AtomicInteger();
        CompletableFuture<Void> done = new CompletableFuture<>();
        writer.write(topic)
                .onPartialResponseWithContext((partial, context) -> {
                    if (task.isCanceled()) {
                        // stop the model's stream too, so a canceled report doesn't keep using (paid) tokens
                        context.streamingHandle().cancel();
                        done.complete(null);
                        return;
                    }
                    task.artifactChunk(artifactId, ARTIFACT, partial.text(), false);
                    chunks.incrementAndGet();
                })
                .onCompleteResponse(response -> {
                    task.artifactChunk(artifactId, ARTIFACT, "", true);
                    done.complete(null);
                })
                .onError(done::completeExceptionally)
                .start();
        try {
            done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException e) {
            if (task.isCanceled()) {
                return;
            }
            throw new IllegalStateException("Report generation failed: " + e.getCause().getMessage(), e.getCause());
        }
        if (task.isCanceled()) {
            return;
        }
        task.complete("Report on \"" + topic + "\" is ready (" + chunks.get() + " chunks)");
    }

    @Override
    public Map<String, Object> metrics() {
        return Map.of("llm", metrics.snapshot());
    }

    /** The placeholder LLM: a fixed report template filled with the topic, streamed word by word. */
    public static RuleBasedStreamingChatModel placeholderModel(Duration tokenDelay) {
        return new RuleBasedStreamingChatModel(request -> {
            String topic = Rules.lastUserText(request);
            return """
                    # Report: %s

                    ## Findings
                    Demand related to %s grew steadily over the period. Most of the growth came from repeat \
                    customers, while new customer acquisition stayed flat. Returns stayed below two percent.

                    ## Recommendation
                    Keep the current assortment for %s, add one bundle offer for repeat customers and review \
                    acquisition channels next quarter.
                    """.formatted(topic, topic, topic);
        }, tokenDelay);
    }
}
