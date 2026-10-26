package com.example.acptest.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;

/**
 * LangChain4j AI service that backs each agent; chat memory is kept per {@code sessionId}. It extends
 * {@link ChatMemoryAccess} so an agent can drop conversations it no longer keeps.
 */
public interface Assistant extends ChatMemoryAccess {

    String chat(@MemoryId String sessionId, @UserMessage String message);
}
