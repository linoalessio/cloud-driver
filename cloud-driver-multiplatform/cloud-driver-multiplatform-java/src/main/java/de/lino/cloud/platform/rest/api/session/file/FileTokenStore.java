package de.lino.cloud.platform.rest.api.session.file;

import de.lino.cloud.platform.rest.api.session.*;
import de.lino.cloud.platform.rest.api.session.linux.LinuxSecretServiceTokenStore;
import de.lino.cloud.platform.rest.api.session.mac.MacKeychainTokenStore;
import de.lino.cloud.platform.rest.api.session.windows.WindowsDpapiTokenStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
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
     * {@inheritDoc} Writes {@code token} to {@link #storageFile} (creating its parent directory
     * first if needed), creating the file - if it doesn't already exist - with {@link #OWNER_ONLY}
     * permissions baked into the creation call itself, so the file is never observable at a
     * world/group-readable permission state at any point, including across a crash between
     * "write" and "restrict permissions". <strong>Fixed a real race (2026-09-05):</strong> the
     * previous implementation wrote the file first, at default OS permissions, and only applied
     * {@link #applyOwnerOnlyPermissions()} as a separate, later step - a crash or kill in between
     * (or simply the race window itself, on a shared machine) left the token world/group-readable.
     * If {@link #storageFile} already exists (e.g. written by a pre-fix version of this class), a
     * file-creation attribute has no effect on it, so {@link #applyOwnerOnlyPermissions()} still
     * runs afterward regardless, healing a file left insecure by that older version.
     *
     * @throws TokenStoreException if creating the parent directory or writing the file fails
     */
    @Override
    public void save(final String token) throws TokenStoreException {
        try {
            Files.createDirectories(this.storageFile.getParent());
            this.writeAtomicallyWithOwnerOnlyPermissions(token);
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

    /**
     * Writes {@code token} to {@link #storageFile} in one call, applying {@link #OWNER_ONLY}
     * permissions at creation time on a filesystem that supports POSIX attributes - never through
     * a separate, later {@code chmod}-equivalent call for a freshly created file. Falls back to a
     * plain write (then a best-effort {@link #applyOwnerOnlyPermissions()}) on a non-POSIX
     * filesystem; {@link TokenStoreFactory} never actually routes to this store on one today (it's
     * only ever chosen on Linux/other Unix, both POSIX), but this keeps {@code save} safe rather
     * than throwing if that ever changes. Always re-applies {@link #OWNER_ONLY} after writing,
     * regardless of whether the file was just created or already existed - a no-op on a freshly
     * created file (already correctly permissioned), and a heal for one left insecure by an older
     * version of this class.
     */
    private void writeAtomicallyWithOwnerOnlyPermissions(final String token) throws IOException {
        final byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        try {
            final FileAttribute<Set<PosixFilePermission>> ownerOnlyAttribute = PosixFilePermissions.asFileAttribute(OWNER_ONLY);
            try (SeekableByteChannel channel = Files.newByteChannel(
                    this.storageFile,
                    EnumSet.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                    ownerOnlyAttribute)) {
                channel.write(ByteBuffer.wrap(bytes));
            }
        } catch (final UnsupportedOperationException notPosix) {
            Files.write(this.storageFile, bytes);
        }
        this.applyOwnerOnlyPermissions();
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
