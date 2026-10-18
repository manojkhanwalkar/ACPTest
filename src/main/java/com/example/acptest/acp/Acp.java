package com.example.acptest.acp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Data model of the Agent Communication Protocol (ACP, https://agentcommunicationprotocol.dev).
 * Only the subset needed for a synchronous request/response run is modelled. Field names are
 * serialized in snake_case to match the ACP OpenAPI spec (e.g. {@code agent_name}, {@code content_type}).
 */
public final class Acp {

    public static final String TEXT_PLAIN = "text/plain";

    /** Shared JSON mapper configured for the ACP wire format. */
    public static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Acp() {
    }

    /** One piece of message content, e.g. a block of plain text. */
    public record MessagePart(String name, String contentType, String content, String contentEncoding,
                              String contentUrl, Map<String, Object> metadata) {

        public static MessagePart text(String text) {
            return new MessagePart(null, TEXT_PLAIN, text, "plain", null, null);
        }
    }

    /** A message is a role plus an ordered list of parts. Roles are "user" or "agent/{name}". */
    public record Message(String role, List<MessagePart> parts, Instant createdAt, Instant completedAt) {

        public static Message user(String text) {
            Instant now = Instant.now();
            return new Message("user", List.of(MessagePart.text(text)), now, now);
        }

        public static Message agent(String agentName, String text) {
            Instant now = Instant.now();
            return new Message("agent/" + agentName, List.of(MessagePart.text(text)), now, now);
        }

        /** Concatenated content of all text/plain parts. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            for (MessagePart part : parts) {
                if (TEXT_PLAIN.equals(part.contentType()) && part.content() != null) {
                    sb.append(part.content());
                }
            }
            return sb.toString();
        }
    }

    /** Self-description an agent publishes for discovery (GET /agents). */
    public record AgentManifest(String name, String description, List<String> inputContentTypes,
                                List<String> outputContentTypes) {
    }

    public record AgentsListResponse(List<AgentManifest> agents) {
    }

    public enum RunMode {
        SYNC("sync"), ASYNC("async"), STREAM("stream");

        private final String wire;

        RunMode(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    public enum RunStatus {
        CREATED("created"), IN_PROGRESS("in-progress"), AWAITING("awaiting"), CANCELLING("cancelling"),
        CANCELLED("cancelled"), COMPLETED("completed"), FAILED("failed");

        private final String wire;

        RunStatus(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }
    }

    /** Body of POST /runs. */
    public record RunCreateRequest(String agentName, String sessionId, List<Message> input, RunMode mode) {
    }

    /** A run: one execution of an agent over some input, carrying the agent's output messages. */
    public record Run(String agentName, String sessionId, String runId, RunStatus status, List<Message> output,
                      Error error, Instant createdAt, Instant finishedAt) {

        public static Run completed(String agentName, String sessionId, List<Message> output, Instant createdAt) {
            return new Run(agentName, sessionId, UUID.randomUUID().toString(), RunStatus.COMPLETED, output, null,
                    createdAt, Instant.now());
        }

        public static Run failed(String agentName, String sessionId, Error error, Instant createdAt) {
            return new Run(agentName, sessionId, UUID.randomUUID().toString(), RunStatus.FAILED, List.of(), error,
                    createdAt, Instant.now());
        }
    }

    /** ACP error object; code is one of "server_error", "invalid_input", "not_found". */
    public record Error(String code, String message) {
    }
}
