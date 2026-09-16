package org.consortium.core.identity;

import org.consortium.core.ConsortiumCore;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;

/**
 * {@code <server root>/consortium/ip-salt.bin}: 32 random bytes, generated on first start, kept outside the world
 * folder on purpose so a copied backup of {@code world/data/consortium_identity.dat} carries no key (specification 2.4).
 */
public final class IpSalt {
    public static final int SIZE = 32;

    private final Path file;
    private byte[] salt;

    public IpSalt(Path serverRoot) {
        this.file = serverRoot.resolve("consortium").resolve("ip-salt.bin").toAbsolutePath();
    }

    public Path file() {
        return file;
    }

    /** Loads the salt, generating it when absent or unreadable. Never throws: an IO failure falls back to a new in-memory salt. */
    public byte[] load() {
        if (salt != null) {
            return salt;
        }
        try {
            if (Files.isRegularFile(file)) {
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length == SIZE) {
                    salt = bytes;
                    return salt;
                }
                ConsortiumCore.LOGGER.warn("{} has {} bytes instead of {}, generating a new salt", file, bytes.length, SIZE);
            }
            salt = generate();
            Files.createDirectories(file.getParent());
            Files.write(file, salt);
            restrictPermissions();
            ConsortiumCore.LOGGER.info("Generated a new identity salt at {}", file);
        } catch (IOException e) {
            ConsortiumCore.LOGGER.error("Cannot read or write {}: {}. Using an in-memory salt for this session (the same-connection check will not persist)", file, e.toString());
            if (salt == null) {
                salt = generate();
            }
        }
        return salt;
    }

    /** Deletes the salt file; the next {@link #load()} generates a fresh one ({@code /ccore identity purge}). */
    public void purge() throws IOException {
        salt = null;
        Files.deleteIfExists(file);
    }

    private void restrictPermissions() {
        // Owner-only permissions where the file system supports them; the call throws on Windows, so it is guarded.
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (IOException | UnsupportedOperationException e) {
                ConsortiumCore.LOGGER.warn("Could not restrict permissions of {}: {}", file, e.toString());
            }
        }
    }

    private static byte[] generate() {
        byte[] bytes = new byte[SIZE];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
