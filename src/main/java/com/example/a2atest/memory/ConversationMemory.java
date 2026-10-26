package com.example.a2atest.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Chat memory per A2A {@code contextId} with limits, so a long-running agent doesn't keep every conversation
 * forever: each conversation keeps its last {@code maxMessages} messages, at most {@code maxConversations}
 * stay active (least recently used are evicted first) and conversations idle for longer than
 * {@code idleTimeout} are evicted.
 * <p>
 * Eviction drops the conversation from the AI service's cache ({@link ChatMemoryAccess#evictChatMemory}). With
 * the default in-memory store its messages are deleted too; with a persistent store such as
 * {@link FileChatMemoryStore} they stay on disk and the conversation resumes when the context is used again.
 * <p>
 * Agents wrap each call to their AI service in {@link #use} (or call {@link #touch} before it), and call
 * {@link #bind} once with the AI service after building it.
 */
public final class ConversationMemory implements ChatMemoryProvider {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);

    private final int maxMessages;
    private final int maxConversations;
    private final Duration idleTimeout;
    private final ChatMemoryStore store;
    private final boolean persistent;
    private final Clock clock;
    private final LinkedHashMap<Object, Instant> lastUsed = new LinkedHashMap<>(16, 0.75f, true);
    // calls in flight per conversation; these are never evicted
    private final Map<Object, Integer> inUse = new HashMap<>();
    private ChatMemoryAccess service;
    private long evictions;

    public ConversationMemory(int maxMessages, int maxConversations, Duration idleTimeout, ChatMemoryStore store,
                              boolean persistent, Clock clock) {
        this.maxMessages = maxMessages;
        this.maxConversations = maxConversations;
        this.idleTimeout = idleTimeout;
        this.store = store;
        this.persistent = persistent;
        this.clock = clock;
    }

    /** Conversations kept only in memory and deleted on eviction. */
    public static ConversationMemory inMemory(int maxMessages, int maxConversations, Duration idleTimeout) {
        return new ConversationMemory(maxMessages, maxConversations, idleTimeout, new InMemoryChatMemoryStore(),
                false, Clock.systemUTC());
    }

    /** Conversations persisted in {@code store}; eviction only frees the in-memory cache. */
    public static ConversationMemory persistent(ChatMemoryStore store, int maxMessages, int maxConversations,
                                                Duration idleTimeout) {
        return new ConversationMemory(maxMessages, maxConversations, idleTimeout, store, true, Clock.systemUTC());
    }

    /** The AI service whose cached memories are evicted; its interface must extend {@link ChatMemoryAccess}. */
    public synchronized void bind(ChatMemoryAccess service) {
        this.service = service;
    }

    @Override
    public ChatMemory get(Object contextId) {
        return MessageWindowChatMemory.builder()
                .id(contextId)
                .maxMessages(maxMessages)
                .chatMemoryStore(store)
                .build();
    }

    /** Marks the conversation as used now and evicts idle and least recently used conversations. */
    public synchronized void touch(Object contextId) {
        Instant now = clock.instant();
        lastUsed.put(contextId, now);
        Iterator<Map.Entry<Object, Instant>> it = lastUsed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Object, Instant> entry = it.next();
            Object id = entry.getKey();
            boolean idle = Duration.between(entry.getValue(), now).compareTo(idleTimeout) > 0;
            // a conversation with a call in flight would be re-created by that call, outside the limit
            if (!id.equals(contextId) && !inUse.containsKey(id) && (idle || lastUsed.size() > maxConversations)) {
                it.remove();
                // evicted under the lock, so a concurrent touch can't revive the id between removal and eviction
                evict(id);
                evictions++;
            }
        }
    }

    /**
     * Touches the conversation and runs {@code call} (the AI service call) while keeping the conversation from
     * being evicted, so a concurrent eviction can't drop or orphan its memory mid-call.
     */
    public <T> T use(Object contextId, Supplier<T> call) {
        synchronized (this) {
            inUse.merge(contextId, 1, Integer::sum);
            touch(contextId);
        }
        try {
            return call.get();
        } finally {
            synchronized (this) {
                inUse.computeIfPresent(contextId, (id, count) -> count == 1 ? null : count - 1);
            }
        }
    }

    private void evict(Object contextId) {
        if (service != null) {
            service.evictChatMemory(contextId);
        }
        if (!persistent) {
            store.deleteMessages(contextId);
        }
        log.debug("Evicted conversation {} ({})", contextId, persistent ? "kept in store" : "deleted");
    }

    public synchronized int activeConversations() {
        return lastUsed.size();
    }

    public synchronized long evictions() {
        return evictions;
    }

    /** Counters for {@code GET /metrics}. */
    public Map<String, Object> snapshot() {
        return Map.of("activeConversations", activeConversations(), "evictedConversations", evictions(),
                "persistentMemory", persistent);
    }
}
