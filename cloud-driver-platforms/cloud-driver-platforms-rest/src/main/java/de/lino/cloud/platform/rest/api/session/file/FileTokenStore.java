package de.lino.cloud.platform.rest.api.session.file;

import de.lino.cloud.platform.rest.api.session.*;
import de.lino.cloud.platform.rest.api.session.linux.LinuxSecretServiceTokenStore;
import de.lino.cloud.platform.rest.api.session.mac.MacKeychainTokenStore;
import de.lino.cloud.platform.rest.api.session.windows.WindowsDpapiTokenStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Last-resort fallback for platforms/environments with no usable OS keychain (e.g. a headless
 * Linux install without {@code secret-tool}/libsecret). Stores the token in a plain file under
 * the user's home directory, restricted to owner-only permissions where the filesystem supports
 * POSIX permissions - <strong>weaker than {@link MacKeychainTokenStore}/{@link
 * WindowsDpapiTokenStore}/{@link LinuxSecretServiceTokenStore}</strong>, since the token sits as
 * plaintext on disk rather than behind OS-level encryption. {@link TokenStoreFactory} only picks
 * this when nothing better is available, and callers should surface that to the user (see its
 * Javadoc).
 */
public final class FileTokenStore implements TokenStore {

    /** The owner-only read/write permission set applied to {@link #storageFile} where the filesystem supports POSIX permissions. */
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
    );

    /** The plain file the session token is read from/written to. */
    private final Path storageFile;

    /** Resolves {@link #storageFile} under the current user's home directory. */
    public FileTokenStore() {
        this.storageFile = Path.of(System.getProperty("user.home"), ".config", "cloud-driver-token", "session.token");
    }

    /**
     * {@inheritDoc} Writes {@code token} as plain text to {@link #storageFile} (creating its
     * parent directory first if needed) and applies owner-only permissions afterward.
     *
     * @throws TokenStoreException if creating the parent directory or writing the file fails
     */
    @Override
    public void save(final String token) throws TokenStoreException {
        try {
            Files.createDirectories(this.storageFile.getParent());
            Files.writeString(this.storageFile, token, StandardCharsets.UTF_8);
            applyOwnerOnlyPermissions();
        } catch (final IOException e) {
            throw new TokenStoreException("@FileTokenStore.save: failed to write " + this.storageFile, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws TokenStoreException if {@link #storageFile} exists but cannot be read
     */
    @Override
    public Optional<String> load() throws TokenStoreException {
        if (!Files.exists(this.storageFile)) {
            return Optional.empty();
        }
        try {
            final String token = Files.readString(this.storageFile, StandardCharsets.UTF_8).strip();
            return token.isEmpty() ? Optional.empty() : Optional.of(token);
        } catch (final IOException e) {
            throw new TokenStoreException("@FileTokenStore.load: failed to read " + this.storageFile, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws TokenStoreException if {@link #storageFile} exists but cannot be deleted
     */
    @Override
    public void clear() throws TokenStoreException {
        try {
            Files.deleteIfExists(this.storageFile);
        } catch (final IOException e) {
            throw new TokenStoreException("@FileTokenStore.clear: failed to delete " + this.storageFile, e);
        }
    }

    /** Best-effort: silently does nothing on filesystems without POSIX permission support. */
    private void applyOwnerOnlyPermissions() {
        try {
            Files.setPosixFilePermissions(this.storageFile, OWNER_ONLY);
        } catch (final UnsupportedOperationException | IOException notPosix) {
            // Best-effort only - nothing further to do on a non-POSIX filesystem.
        }
    }

}
