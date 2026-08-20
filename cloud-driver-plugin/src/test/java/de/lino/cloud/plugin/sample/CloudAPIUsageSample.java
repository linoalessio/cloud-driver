package de.lino.cloud.plugin.sample;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.meta.FileMetadata;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.crypto.EncryptedPayload;
import de.lino.cloud.api.security.envelope.EnvelopeEncryptedPayload;
import de.lino.cloud.api.security.hash.HashAlgorithm;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.security.password.PasswordHasher;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.plugin.DefaultCloudAPI;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.hash.Hasher;
import de.lino.cloud.plugin.security.keys.InMemoryKeyEncryptionService;
import de.lino.cloud.plugin.security.password.Argon2idPasswordHasher;
import de.lino.cloud.plugin.security.secrets.SecretRedactor;
import de.lino.database.DatabaseRepository;
import de.lino.database.DatabaseRepositoryRegistry;
import de.lino.database.database.DatabaseProvider;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.entity.Serialized;
import de.lino.database.database.file.DefaultFileProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Worked example of {@link CloudAPI}: wire up a database-backed {@link
 * DatabaseSection} (here, the file-based JSON provider from {@code
 * database-driver-plugin}, so this sample needs no external database
 * server), initialize {@link CloudAPI} against it, then send and receive a
 * domain meta through the singleton.
 *
 * <p>This class is demo code, not part of the module's published API - it
 * lives under {@code src/test} so it is never shipped in the built jar. Run
 * {@link #main} directly to see it end-to-end.
 */
public final class CloudAPIUsageSample {

    /**
     * A minimal {@link Serialized} domain meta. Any extension-defined
     * subclass works the same way - {@link DataFactory#register} and {@link
     * DataFactory#fetch} are generic over every {@link Serialized} type.
     */
    static final class CustomerRecord extends Serialized {

        private final int id;
        private final String iban;

        CustomerRecord(final int id, final String iban) {
            this.id = id;
            this.iban = iban;
        }

        @Override
        public List<String> keysOf() {
            return List.of(String.valueOf(id));
        }

        @Override
        public String toString() {
            return "CustomerRecord{id=" + id + ", iban='" + iban + "'}";
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof CustomerRecord that && id == that.id && Objects.equals(iban, that.iban);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, iban);
        }
    }

    public static void main(final String[] args) throws Exception {

        // --- 1. Wire up a database ------------------------------------------
        // Any database-driver-plugin DatabaseProvider works here (JSON file
        // store, H2, MySQL, PostgreSQL, MongoDB, ...); the JSON provider is
        // used here only because it needs no external server to run this
        // sample. DefaultFileProvider installs the plugin's file-system
        // singleton, a one-time bootstrap step the plugin requires before any
        // file-based provider is constructed.
        new DefaultFileProvider();
        new DatabaseRepositoryRegistry(true);

        final Path repositoryRoot = Path.of(System.getProperty("java.io.tmpdir"), "cloud-driver-sample-db");
        final Credentials credentials = new Credentials(repositoryRoot.resolve("credentials.json"), repositoryRoot.resolve("data"));
        final DatabaseProvider databaseProvider = DatabaseRepository.getInstance().registerDatabaseProvider(0, DatabaseType.JSON, credentials);
        // No section is created here: EntityDatabaseClient creates (or reuses)
        // one section per meta type on demand, named after the type - e.g.
        // CustomerRecord below ends up in a "CustomerRecord" section.

        // --- 2. Wire up envelope encryption ---------------------------------
        // In production, InMemoryKeyEncryptionService is replaced by a client
        // for a real KMS/HSM - see its Javadoc. Kept as its own variable
        // (rather than inlined) so step 9 below can rotate it directly.
        final KeyEncryptionService keyEncryptionService = new InMemoryKeyEncryptionService();
        final EnvelopeEncryptionService envelopeEncryptionService = new EnvelopeEncryptionService(keyEncryptionService);

        // --- 3. Initialize the CloudAPI singleton ---------------------------
        final CloudAPI cloudAPI = DefaultCloudAPI.setInstance(databaseProvider, envelopeEncryptionService);
        // Entity persistence lives behind CloudAPI#getDataFactory(); extension
        // lifecycle management (not used in this sample) behind #getExtensionFactory().
        final DataFactory dataFactory = cloudAPI.getDataFactory();

        // --- 4. Register an meta - encrypted before it is written to the database
        final CustomerRecord customer = new CustomerRecord(42, "DE89370400440532013000");
        dataFactory.registerAsync(customer).get();
        System.out.println("Stored " + customer);

        // --- 5. Fetch it back - decrypted after authentication succeeds -----
        // Asserts.assertNotNull(CloudAPI) validates the singleton is installed before
        // use, giving a clear error instead of a bare NullPointerException if it isn't.
        final CustomerRecord recovered = Asserts.assertNotNull(CloudAPI.getInstance()).getDataFactory().fetch("42", CustomerRecord.class);
        System.out.println("Recovered " + recovered + " (equals original: " + customer.equals(recovered) + ")");

        // --- 6. update() overwrites the existing record under the same id ----
        final CustomerRecord movedAccount = new CustomerRecord(42, "DE02120300000000202051");
        dataFactory.update(movedAccount);
        System.out.println("Updated to " + dataFactory.fetch("42", CustomerRecord.class));

        // --- 7. An unknown id is rejected, not silently returned as null ----
        try {
            dataFactory.fetch("does-not-exist", CustomerRecord.class);
        } catch (final DatabaseClientException expected) {
            System.out.println("Unknown id correctly rejected: " + expected.getMessage());
        }

        // --- 7b. update() also rejects an id that was never registered -----
        try {
            dataFactory.update(new CustomerRecord(99, "DE00000000000000000000"));
            throw new IllegalStateException("expected update() of an unknown id to be rejected, but it succeeded");
        } catch (final DatabaseClientException expected) {
            System.out.println("update() of an unknown id correctly rejected: " + expected.getMessage());
        }

        // --- 7c. findById() reports absence as Optional.empty(), not an exception
        System.out.println("findById(does-not-exist): " + dataFactory.findById("does-not-exist", CustomerRecord.class));
        System.out.println("findById(42): " + dataFactory.findById("42", CustomerRecord.class));

        // The steps above only exercise the security package indirectly, through
        // CloudAPI/EntityDatabaseClient. The remaining steps use it directly.

        // --- 8. Hashing (security.hash) - an integrity hash for audit logging,
        // computed over the meta's own serialized bytes (section 6/7).
        final byte[] integrityHash = Hasher.digest(HashAlgorithm.SHA_256, movedAccount.toByteArray());
        System.out.println("SHA-256 of the stored record's serialized bytes: " + HexFormat.of().formatHex(integrityHash));

        // --- 9. Key rotation (security.keys) - activating a new key-encryption
        // key does not invalidate data already wrapped under the previous one.
        final String previousKeyEncryptionKeyId = keyEncryptionService.activeKeyEncryptionKeyId();
        final String rotatedKeyEncryptionKeyId = keyEncryptionService.rotate();
        System.out.println("Rotated key-encryption key: " + previousKeyEncryptionKeyId + " -> " + rotatedKeyEncryptionKeyId);
        System.out.println("Entity wrapped under the old key still decrypts: " + dataFactory.fetch("42", CustomerRecord.class));

        // --- 9b. Delete - removes the meta from the database and its cache;
        // a subsequent fetch of the same id is rejected like any unknown id.
        dataFactory.delete("42", CustomerRecord.class);
        try {
            dataFactory.fetch("42", CustomerRecord.class);
            throw new IllegalStateException("expected the deleted id to be rejected, but fetch() succeeded");
        } catch (final DatabaseClientException expected) {
            System.out.println("Deleted id correctly rejected: " + expected.getMessage());
        }

        // --- 9c. File upload/download (de.lino.cloud.api.file / FileFactory) -
        // StoredFile is itself a Serialized meta, so FileFactory persists it
        // through the very same DataFactory used above (accepting any content
        // type, envelope-encrypted the same way any other meta is) and adds
        // only upload/download naming plus a checksum check on every download,
        // independent of the AES-GCM authentication tag DataFactory#fetch
        // already verifies.
        final FileFactory fileFactory = cloudAPI.getFileFactory();
        final StoredFile report = new StoredFile(
                "report-1", "quarterly-report.pdf",
                "not a real PDF, just demo bytes".getBytes(StandardCharsets.UTF_8)
        );
        fileFactory.uploadAsync(report).get();
        System.out.println("Uploaded " + report);

        final StoredFile downloaded = fileFactory.download("report-1");
        System.out.println("Downloaded " + downloaded
                + " (content matches original: " + Arrays.equals(report.content(), downloaded.content()) + ")");

        // metadata() returns the same descriptive attributes without holding
        // the file's decrypted content in memory.
        final FileMetadata metadata = fileFactory.metadata("report-1").orElseThrow();
        System.out.println("Metadata only: " + metadata);

        // getEntities() lists and integrity-verifies every currently stored file.
        System.out.println("getEntities(): " + fileFactory.getEntities());

        // An unknown file id is reported as absence, not an exception.
        System.out.println("findById(does-not-exist): " + fileFactory.findById("does-not-exist"));

        fileFactory.delete("report-1");
        try {
            fileFactory.download("report-1");
            throw new IllegalStateException("expected the deleted file id to be rejected, but download() succeeded");
        } catch (final DatabaseClientException expected) {
            System.out.println("Deleted file id correctly rejected: " + expected.getMessage());
        }

        // --- 9d. downloadToDevice() - re-creates a StoredFile on disk under its
        // own file name (preserving its suffix) inside a destination directory,
        // the inverse of reading a local file's bytes to upload it.
        fileFactory.upload(report);
        final Path downloadDirectory = Path.of(System.getProperty("java.io.tmpdir"), "cloud-driver-sample-downloads");
        final Path downloadedPath = fileFactory.download("report-1").downloadToDevice(downloadDirectory);
        System.out.println("Downloaded to device: " + downloadedPath
                + " (bytes match: " + Arrays.equals(report.content(), Files.readAllBytes(downloadedPath)) + ")");
        Files.deleteIfExists(downloadedPath);
        Files.deleteIfExists(downloadDirectory);

        // --- 9e. clear()/deleteSection() - clear() empties the StoredFile
        // section but leaves it in place; deleteSection() removes the section
        // itself, which a later upload() lazily recreates.
        fileFactory.upload(new StoredFile("report-2", "second.txt", "more demo bytes".getBytes(StandardCharsets.UTF_8)));
        fileFactory.clear();
        System.out.println("findById(report-1) after clear(): " + fileFactory.findById("report-1"));
        System.out.println("findById(report-2) after clear(): " + fileFactory.findById("report-2"));

        fileFactory.upload(report);
        fileFactory.deleteSectionAsync().get();
        System.out.println("findById(report-1) after deleteSection(): " + fileFactory.findById("report-1"));
        fileFactory.upload(report);
        System.out.println("Section lazily recreated - re-upload succeeded: " + fileFactory.findById("report-1").isPresent());
        fileFactory.deleteSection();

        // --- 10. Authenticated encryption (security.crypto/envelope) - a payload
        // tampered with after encryption fails authentication and is rejected,
        // rather than silently returning corrupted plaintext.
        final EnvelopeEncryptedPayload envelope = envelopeEncryptionService.encrypt(
                "internal note, unrelated to any stored meta".getBytes(StandardCharsets.UTF_8),
                "sample-context".getBytes(StandardCharsets.UTF_8)
        );
        final byte[] tamperedCiphertext = envelope.payload().ciphertext();
        tamperedCiphertext[0] ^= 0x01;
        final EncryptedPayload tamperedPayload = new EncryptedPayload(
                envelope.payload().algorithmId(), envelope.payload().nonce(), tamperedCiphertext, envelope.payload().associatedData()
        );
        try {
            envelopeEncryptionService.decrypt(
                    new EnvelopeEncryptedPayload(envelope.schemaVersion(), envelope.wrappedDataEncryptionKey(), tamperedPayload)
            );
            throw new IllegalStateException("expected tampering to be rejected, but decrypt() succeeded");
        } catch (final AuthenticationFailedException expected) {
            System.out.println("Tampered payload correctly rejected: " + expected.getMessage());
        }

        // --- 11. Password hashing (security.password) - only relevant if this
        // extension must store a password itself; prefer OAuth 2.0 client
        // credentials or mTLS for service-to-service authentication instead.
        final PasswordHasher passwordHasher = new Argon2idPasswordHasher();
        final String encodedPasswordHash = passwordHasher.hash("correct horse battery staple".toCharArray());
        System.out.println("Argon2id hash of a sample admin credential: " + encodedPasswordHash);
        System.out.println("Verifies against the original password: "
                + passwordHasher.verify("correct horse battery staple".toCharArray(), encodedPasswordHash));

        // --- 12. Secret redaction (security.secrets) - strip secrets out of
        // text before it is logged or surfaced in an error message.
        final String rawLogLine = "Authorization: Bearer sample-secret-token processing request for customer 42";
        System.out.println("Redacted before logging: " + SecretRedactor.redact(rawLogLine));
    }
}
