package com.example.a2atest.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * A LangChain4j {@link ChatMemoryStore} that keeps each conversation as a JSON file, so conversations survive
 * memory eviction and server restarts. Conversations can contain personal data, so on POSIX file systems the
 * directory is owner-only ({@code rwx------}) and every file {@code rw-------}. File names are the SHA-256 of
 * the memory id: fixed length whatever the id, and they don't reveal it.
 */
public final class FileChatMemoryStore implements ChatMemoryStore {

    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final Path directory;

    public FileChatMemoryStore(Path directory) {
        this.directory = directory;
        try {
            if (POSIX) {
                Files.createDirectories(directory, perms("rwx------"));
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            } else {
                Files.createDirectories(directory);
            }
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
            Path tmp = POSIX ? Files.createTempFile(directory, "conversation", ".tmp", perms("rw-------"))
                    : Files.createTempFile(directory, "conversation", ".tmp");
            try {
                Files.writeString(tmp, ChatMessageSerializer.messagesToJson(messages), StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
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
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(memoryId.toString().getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest) + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FileAttribute<Set<PosixFilePermission>> perms(String spec) {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(spec));
    }
}
