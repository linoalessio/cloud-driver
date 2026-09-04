package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.security.keys.KeyEncryptionService;
import de.lino.cloud.api.storage.object.ObjectStorageService;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.AwsKmsKeyEncryptionService;
import de.lino.cloud.plugin.storage.object.StoredFileContentChannel;
import de.lino.database.database.DatabaseType;
import de.lino.database.database.auth.Credentials;
import de.lino.database.database.sql.SQLExecution;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;
import software.amazon.awssdk.regions.Region;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One-off, operator-triggered migration of every not-yet-S3-backed {@link StoredFile}'s content
 * onto the configured {@link ObjectStorageService} - {@code architecture/AWS_S3_IMPL.md} section
 * 7. Modeled on {@link RecomputeStorageCommand}'s "operator-triggered, not automatic, print
 * progress via {@link Terminal#displayApproved}" shape; never wired to run on startup or on any
 * schedule, matching the handoff document's explicit instruction to ship this as a separate,
 * independently-verifiable unit of work from the code that starts using S3 for new uploads.
 *
 * <p><b>Why this module now depends on {@code cloud-driver-plugin}/{@code database-driver-plugin}
 * directly</b> - a first for {@code cloud-driver-extensions-terminal}, whose every other {@code
 * Command} only ever reaches {@code cloud-driver-api} types through {@link
 * CloudDriver#getInstance()}: this command needs {@link EnvelopeEncryptionService}/{@link
 * StoredFileContentChannel} to encrypt a file's content the exact same way {@code
 * DefaultFileFactory#upload} does (both plugin-level types, by design - see {@code
 * StoredFileContentChannel}'s own Javadoc for why that encryption step can't be reached through
 * any {@code cloud-driver-api} facade), and {@link SQLExecution} to page through every {@code
 * StoredFile} id without loading full, still-encrypted content into memory for rows this run
 * doesn't even need to touch (see {@link #resolveStoredFileTableName}/{@link #fetchIdPage} - the
 * same {@code DatabaseBackupScheduler}-style keyset-pagination the handoff document calls for,
 * deliberately not {@code DataFactory#getEntities}, which would defeat the whole point).
 * {@code cloud-driver-extensions-backup}/{@code -rest}/{@code -metrics} already established this
 * "an extension module depends on {@code cloud-driver-plugin} when it genuinely needs a
 * plugin-level type" precedent - this command is simply the first place {@code
 * cloud-driver-extensions-terminal} needed it too.
 *
 * <p><b>Resolves its own, independent {@link EnvelopeEncryptionService}/{@link
 * ObjectStorageService} rather than reaching for whatever {@code CloudBootstrap} wired in at
 * boot</b> for the encryption half (there is no {@code cloud-driver-api}-level facade exposing
 * the live {@link EnvelopeEncryptionService} instance - see this class's own Javadoc above), but
 * <b>does</b> reuse the live {@link ObjectStorageService} published on {@link
 * CloudDriver#getInstance()}'s {@code IFactoryContainer} for the actual S3 write, so this command
 * only ever runs at all against a deployment that has genuinely opted into S3-backed storage.
 * Resolving a second {@link AwsKmsKeyEncryptionService} against the same {@code
 * "aws-kms-region"}/{@code "aws-kms-key-id"} {@code configuration.json} keys {@code CloudBootstrap}
 * itself reads is safe and produces an equivalent instance - KMS {@code Encrypt}/{@code Decrypt}
 * is a stateless, per-call operation keyed only by the KMS key id, not by which Java object issued
 * the call.
 *
 * <p><b>Idempotent and resumable by construction, not by tracking progress explicitly</b> - every
 * run re-scans the whole id range from scratch (see {@link #fetchIdPage}) and skips any row
 * that's already {@link StoredFile#isS3Backed()}, so a crash mid-run (or simply running this
 * command again later, e.g. against files uploaded since the last run while S3-backed storage
 * wasn't configured yet) always converges toward "every row migrated" rather than needing its own
 * separate resume-point bookkeeping.
 */
public final class MigrateToS3Command implements Command {

    /** Rows fetched per keyset page - bounds how many ids (not full files) are held in memory at once, unrelated to how many files the table actually holds. */
    private static final int PAGE_SIZE = 200;

    @Override
    public @NotNull String name() {
        return "migrateToS3";
    }

    @Override
    public @NotNull List<String> aliases() {
        return List.of("migrateS3");
    }

    @Override
    public @NotNull String description() {
        return "Migrates every not-yet-S3-backed StoredFile's content onto the configured S3 bucket (architecture/AWS_S3_IMPL.md)";
    }

    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();

        final ObjectStorageService objectStorageService = CloudDriver.getInstance().getFactoryContainer().getObjectStorageService();
        if (objectStorageService == null) {
            terminal.displayApproved(
                    "&cNo ObjectStorageService is configured on this deployment - nothing to migrate. "
                            + "Set 'aws-s3-bucket'/'aws-s3-region' in configuration.json and restart first.");
            return;
        }

        final DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();
        final StoredFileContentChannel contentChannel = new StoredFileContentChannel(resolveEnvelopeEncryptionService());

        final Credentials postgresCredentials = Credentials.of(Constraints.CONFIGURATION_PATH.resolve("postgres-database.json")).orElseThrow();
        final SQLExecution sqlExecution = new SQLExecution(DatabaseType.POSTGRES_SQL, postgresCredentials);

        try {
            runMigration(terminal, dataFactory, objectStorageService, contentChannel, sqlExecution);
        } finally {
            sqlExecution.shutdown();
        }
    }

    /**
     * Resolves an {@link EnvelopeEncryptionService} the same way {@code CloudBootstrap.initiateCloudDriver()}
     * does - reading {@code "aws-kms-region"}/{@code "aws-kms-key-id"} off {@link
     * CloudDriver#getInstance()}'s own {@link CloudDriver#getConfiguration()} - see this class's
     * own Javadoc for why a fresh instance, rather than the live one, is both necessary and safe.
     */
    private static EnvelopeEncryptionService resolveEnvelopeEncryptionService() {
        final JsonDocument configuration = CloudDriver.getInstance().getConfiguration();
        final String keyId = configuration.getString("aws-kms-key-id");
        final Region region = Region.of(configuration.getString("aws-kms-region"));
        final KeyEncryptionService keyEncryptionService = new AwsKmsKeyEncryptionService(region, keyId);
        return new EnvelopeEncryptionService(keyEncryptionService);
    }

    /**
     * Pages through every {@code StoredFile} id via {@link #fetchIdPage}, migrating each
     * not-yet-{@link StoredFile#isS3Backed()} row found (see {@link #migrateFile}), reporting
     * running progress after every page.
     */
    private void runMigration(final Terminal terminal, final DataFactory dataFactory, final ObjectStorageService objectStorageService,
                               final StoredFileContentChannel contentChannel, final SQLExecution sqlExecution) {

        final String tableName = resolveStoredFileTableName(sqlExecution);
        if (tableName == null) {
            terminal.displayApproved("&cCould not resolve the StoredFile table in information_schema.tables - nothing to migrate (has any file ever been uploaded?).");
            return;
        }

        terminal.displayApproved("Starting S3 migration for table '&b%s&7'...", tableName);

        long migrated = 0;
        long alreadyMigrated = 0;
        long failed = 0;
        long bytesMoved = 0;
        String lastId = null;

        while (true) {

            final List<String> ids = fetchIdPage(sqlExecution, tableName, lastId);
            if (ids.isEmpty()) break;

            for (final String id : ids) {
                try {
                    final Optional<StoredFile> maybeFile = dataFactory.findById(id, StoredFile.class);
                    if (maybeFile.isEmpty()) {
                        continue; // raced away between the id listing above and this lookup - nothing to migrate
                    }

                    final StoredFile file = maybeFile.get();
                    if (file.isS3Backed()) {
                        alreadyMigrated++;
                        continue;
                    }

                    bytesMoved += migrateFile(dataFactory, objectStorageService, contentChannel, file);
                    migrated++;
                } catch (final Exception migrationFailed) {
                    failed++;
                    terminal.displayApproved("&cFailed to migrate file '&b%s&7': &c%s", id, migrationFailed.getMessage());
                }
            }

            terminal.displayApproved(
                    "&8...&7 migrated &b%s&7, already S3-backed &b%s&7, failed &b%s&7 so far (&b%s&7 moved)",
                    migrated, alreadyMigrated, failed, Constraints.resolveBytesToUnit(bytesMoved));

            lastId = ids.get(ids.size() - 1);
            if (ids.size() < PAGE_SIZE) break; // last, not-full page - done
        }

        terminal.displayApproved(
                "S3 migration finished: &b%s&7 migrated, &b%s&7 already S3-backed, &b%s&7 failed (&b%s&7 moved)",
                migrated, alreadyMigrated, failed, Constraints.resolveBytesToUnit(bytesMoved));
    }

    /**
     * Migrates one file: encrypts its raw storable bytes via {@code contentChannel}, writes the
     * result to {@code objectStorageService} under its own id, and only then - the S3 write
     * already confirmed successful - persists the metadata-only copy via {@link
     * DataFactory#update} (architecture/AWS_S3_IMPL.md section 7, step 4: never null out {@code
     * contentBase64} before the S3 write it depends on has actually succeeded).
     *
     * @return the number of raw bytes moved to object storage
     */
    private static long migrateFile(final DataFactory dataFactory, final ObjectStorageService objectStorageService,
                                     final StoredFileContentChannel contentChannel, final StoredFile file) throws Exception {
        final byte[] rawBytes = file.rawStorableBytes();
        final byte[] encrypted = contentChannel.send(file.fileId(), rawBytes);
        objectStorageService.putObject(file.fileId(), encrypted);

        final StoredFile metadataOnly = file.withObjectStorageKey(file.fileId());
        dataFactory.update(metadataOnly);

        return rawBytes.length;
    }

    /**
     * Resolves {@code StoredFile}'s real, Postgres-normalized table name via {@code
     * information_schema.tables} rather than assuming a naming convention (the exact
     * capitalization {@code database-driver-plugin}'s {@code SQLDatabaseSection} creates a table
     * under - quoted or folded to lowercase by unquoted DDL - is an internal detail of that
     * artifact, not documented as a stable contract here; the same reasoning {@code
     * DatabaseBackupScheduler#listTables} already applies).
     *
     * @return the resolved table name, or {@code null} if no table matches
     */
    private static String resolveStoredFileTableName(final SQLExecution sqlExecution) {
        return sqlExecution.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND lower(table_name) = lower(?)",
                resultSet -> {
                    try {
                        return resultSet.next() ? resultSet.getString("table_name") : null;
                    } catch (final SQLException exception) {
                        throw new RuntimeException(exception);
                    }
                },
                null,
                StoredFile.class.getSimpleName()
        );
    }

    /**
     * Loads the next page of {@code tableName}'s own ids, up to {@link #PAGE_SIZE}, starting
     * after {@code afterId} (exclusive), sorted ascending - the same keyset-pagination shape
     * {@code DatabaseBackupScheduler#fetchBatch} uses, applied here to just the {@code id} column
     * rather than full rows, since a row's own (still-encrypted) content is irrelevant to this
     * query and would otherwise be pulled across the wire for nothing.
     *
     * @param afterId the last id read on the previous page, or {@code null} for the first page
     * @return up to {@link #PAGE_SIZE} ids, in ascending order
     */
    private static List<String> fetchIdPage(final SQLExecution sqlExecution, final String tableName, final String afterId) {

        final String quotedTable = quoteIdentifier(tableName);
        final String query = afterId == null
                ? "SELECT id FROM " + quotedTable + " ORDER BY id ASC LIMIT ?"
                : "SELECT id FROM " + quotedTable + " WHERE id > ? ORDER BY id ASC LIMIT ?";
        final Object[] parameters = afterId == null ? new Object[]{PAGE_SIZE} : new Object[]{afterId, PAGE_SIZE};

        return sqlExecution.executeQuery(query, resultSet -> {
            final List<String> ids = new ArrayList<>();
            try {
                while (resultSet.next()) {
                    ids.add(resultSet.getString("id"));
                }
            } catch (final SQLException exception) {
                throw new RuntimeException(exception);
            }
            return ids;
        }, List.of(), parameters);
    }

    /**
     * Quotes {@code identifier} as a safe SQL identifier (double-quoted, embedded double quotes
     * escaped), so the table name resolved by {@link #resolveStoredFileTableName} can be
     * interpolated directly into a query - the same helper {@code DatabaseBackupScheduler} uses.
     */
    private static String quoteIdentifier(final String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

}
