package com.example.a2atest.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/** LangChain4j AI service that backs each agent; chat memory is kept per A2A {@code contextId}. */
public interface Assistant {

    String chat(@MemoryId String contextId, @UserMessage String message);
}
