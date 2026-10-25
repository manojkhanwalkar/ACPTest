package com.example.a2atest.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.memory.ChatMemoryAccess;

/** LangChain4j AI service that backs each agent; chat memory is kept per A2A {@code contextId}. */
public interface Assistant extends ChatMemoryAccess {

    String chat(@MemoryId String contextId, @UserMessage String message);
}
