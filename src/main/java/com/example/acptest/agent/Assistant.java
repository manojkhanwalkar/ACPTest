package com.example.acptest.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/** LangChain4j AI service that backs each agent; chat memory is kept per {@code sessionId}. */
public interface Assistant {

    String chat(@MemoryId String sessionId, @UserMessage String message);
}
