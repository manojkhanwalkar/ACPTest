package com.example.a2atest.agents.knowledge;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A LangChain4j {@link ContentRetriever} that ranks document segments by how many of the query's keywords
 * they contain. It needs no embedding model, which keeps the example offline and deterministic; swapping in an
 * {@code EmbeddingStoreContentRetriever} gives semantic search without touching the agent.
 */
public final class KeywordContentRetriever implements ContentRetriever {

    public static final String FILE_NAME = "file_name";

    private static final Set<String> STOPWORDS = Set.of("what", "which", "when", "where", "your", "about", "does",
            "have", "with", "this", "that", "there", "their", "from", "into", "will", "would", "could", "should",
            "much", "many", "long", "tell", "please", "policy", "the", "and", "for", "can", "how", "you", "are",
            "our", "any", "get");

    private record Indexed(TextSegment segment, Set<String> terms) {
    }

    private final List<Indexed> index = new ArrayList<>();
    private final int maxResults;

    public KeywordContentRetriever(List<Document> documents, int maxResults) {
        this.maxResults = maxResults;
        for (TextSegment segment : DocumentSplitters.recursive(300, 0).splitAll(documents)) {
            index.add(new Indexed(segment, terms(segment.text())));
        }
    }

    /** Loads markdown documents from the classpath, e.g. {@code knowledge/shipping.md}. */
    public static List<Document> loadClasspath(String directory, List<String> fileNames) {
        List<Document> documents = new ArrayList<>();
        for (String name : fileNames) {
            String path = directory + "/" + name;
            try (InputStream in = KeywordContentRetriever.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalArgumentException("Missing classpath resource " + path);
                }
                documents.add(Document.from(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                        Metadata.from(FILE_NAME, name)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return documents;
    }

    @Override
    public List<Content> retrieve(Query query) {
        Set<String> wanted = terms(query.text());
        record Scored(TextSegment segment, long score) {
        }
        return index.stream()
                .map(i -> new Scored(i.segment(), i.terms().stream().filter(wanted::contains).count()))
                .filter(s -> s.score() > 0)
                .sorted(Comparator.comparingLong(Scored::score).reversed())
                .limit(maxResults)
                .map(s -> Content.from(s.segment()))
                .toList();
    }

    private static Set<String> terms(String text) {
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() > 2 && !STOPWORDS.contains(t))
                .map(KeywordContentRetriever::stem)
                .collect(Collectors.toSet());
    }

    /** Crude stemming so "returns", "returned" and "return" match. */
    private static String stem(String term) {
        for (String suffix : List.of("ing", "ed", "es", "s")) {
            if (term.length() > suffix.length() + 3 && term.endsWith(suffix)) {
                return term.substring(0, term.length() - suffix.length());
            }
        }
        return term;
    }
}
