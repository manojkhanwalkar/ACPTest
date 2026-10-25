package com.example.a2atest.agents.knowledge;

import com.example.a2atest.a2a.A2a;
import com.example.a2atest.a2a.A2aAgent;
import com.example.a2atest.a2a.RequestContext;
import com.example.a2atest.a2a.TaskUpdater;
import com.example.a2atest.llm.LlmConfig;
import com.example.a2atest.llm.LlmMetrics;
import com.example.a2atest.llm.RuleBasedChatModel;
import com.example.a2atest.llm.Rules;
import com.example.a2atest.memory.ConversationMemory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Store-policy agent. Shows <b>retrieval-augmented generation</b>: every question goes through a LangChain4j
 * {@link ContentRetriever} over the documents in {@code src/main/resources/knowledge}; the retrieved
 * segments are added to the prompt and the LLM answers only from them. The reply carries the answer as text and
 * the source file names as a data part ({@code {"sources": [...]}}).
 */
public final class KnowledgeAgent implements A2aAgent {

    public static final String NAME = "knowledge-agent";
    public static final List<String> DOCUMENTS = List.of("returns-policy.md", "shipping.md", "warranty.md");

    interface KnowledgeAssistant extends ChatMemoryAccess {

        @SystemMessage("""
                You answer questions about the shop's policies for other agents.
                Answer only from the information provided with the question, in one or two sentences.
                If it doesn't contain the answer, say that you don't know.""")
        Result<String> answer(@MemoryId String contextId, @UserMessage String question);
    }

    /** Data part of the reply. */
    public record Sources(List<String> sources) {
    }

    private final KnowledgeAssistant assistant;
    private final ConversationMemory memory = ConversationMemory.inMemory(10, 1000, Duration.ofMinutes(30));
    private final LlmMetrics metrics = new LlmMetrics(NAME);

    public KnowledgeAgent() {
        this(LlmConfig.fromEnv(), KeywordContentRetriever.loadClasspath("knowledge", DOCUMENTS));
    }

    public KnowledgeAgent(LlmConfig llm, List<Document> documents) {
        assistant = AiServices.builder(KnowledgeAssistant.class)
                .chatModel(llm.chatModel(placeholderModel(), List.of(metrics)))
                .chatMemoryProvider(memory)
                .contentRetriever(new KeywordContentRetriever(documents, 2))
                .build();
        memory.bind(assistant);
    }

    @Override
    public A2a.AgentCard card(String url) {
        return A2a.AgentCard.of(NAME, "Answers questions about returns, shipping and warranty from the policy "
                        + "documents, citing its sources", url, false, List.of(A2a.TEXT_PLAIN, A2a.APPLICATION_JSON),
                List.of(new A2a.AgentSkill("policy-qa", "Policy Q&A", "Answers grounded in the shop's policy documents",
                        List.of("rag", "qa"), List.of("How long do I have to return headphones?"),
                        List.of(A2a.TEXT_PLAIN), List.of(A2a.TEXT_PLAIN, A2a.APPLICATION_JSON))));
    }

    @Override
    public void execute(RequestContext request, TaskUpdater task) {
        memory.touch(request.memoryKey());
        Result<String> result = assistant.answer(request.memoryKey(), request.message().text());
        List<String> sources = result.sources().stream()
                .map(Content::textSegment)
                .map(segment -> segment.metadata().getString(KeywordContentRetriever.FILE_NAME))
                .distinct()
                .toList();
        task.complete(List.of(A2a.Part.text(result.content()), A2a.Part.data(new Sources(sources))));
    }

    @Override
    public Map<String, Object> metrics() {
        return Map.of("llm", metrics.snapshot(), "memory", memory.snapshot());
    }

    private static final String RAG_MARKER = "Answer using the following information:";

    /**
     * The placeholder LLM: answers with the best-ranked retrieved segment, or says it doesn't know when the
     * retriever found nothing (then LangChain4j adds no information to the prompt).
     */
    public static RuleBasedChatModel placeholderModel() {
        return new RuleBasedChatModel(request -> {
            String text = Rules.lastUserText(request);
            int marker = text.indexOf(RAG_MARKER);
            if (marker < 0) {
                return AiMessage.from("I don't know; none of the policy documents cover that.");
            }
            // segments are ranked best first; answer with the first paragraph that isn't a markdown heading
            String information = text.substring(marker + RAG_MARKER.length()).replaceAll("(?m)^#+ .*$", "");
            String best = Arrays.stream(information.split("\n\n")).map(String::trim)
                    .filter(p -> !p.isEmpty()).findFirst().orElse("");
            return AiMessage.from("According to our policy: " + best);
        });
    }
}
