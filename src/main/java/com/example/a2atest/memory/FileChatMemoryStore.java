package com.example.a2atest.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;
import java.util.List;

/**
 * A LangChain4j {@link ChatMemoryStore} that keeps each conversation as a JSON file, so conversations survive
 * memory eviction and server restarts. File names are the hex-encoded context id.
 */
public final class FileChatMemoryStore implements ChatMemoryStore {

    private final Path directory;

    public FileChatMemoryStore(Path directory) {
        this.directory = directory;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized List<ChatMessage> getMessages(Object memoryId) {
        Path file = file(memoryId);
        try {
            return Files.exists(file)
                    ? ChatMessageDeserializer.messagesFromJson(Files.readString(file, StandardCharsets.UTF_8))
                    : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void updateMessages(Object memoryId, List<ChatMessage> messages) {
        Path file = file(memoryId);
        try {
            Path tmp = Files.createTempFile(directory, "conversation", ".tmp");
            Files.writeString(tmp, ChatMessageSerializer.messagesToJson(messages), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized void deleteMessages(Object memoryId) {
        try {
            Files.deleteIfExists(file(memoryId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(Object memoryId) {
        return directory.resolve(HexFormat.of().formatHex(memoryId.toString().getBytes(StandardCharsets.UTF_8))
                + ".json");
    }
}
