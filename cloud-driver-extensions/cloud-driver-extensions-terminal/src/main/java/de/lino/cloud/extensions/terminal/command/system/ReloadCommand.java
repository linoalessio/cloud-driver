package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.auth.entity.CloudUser;
import de.lino.cloud.auth.entity.SharedFileGrant;
import de.lino.cloud.auth.entity.SharedFolderGrant;
import de.lino.cloud.auth.entity.StoredFileOwnership;
import de.lino.database.database.entity.Serialized;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Re-reads one entity type's section from the database, discarding this process's cached mirror.
 *
 * <h2>What this is for</h2>
 *
 * Every {@code DatabaseSection} keeps an in-process mirror that is loaded once and thereafter kept
 * current only by writes made through that same instance. A row written by a <em>different</em>
 * process is therefore invisible to this one indefinitely - not for a cache TTL, but until
 * something reloads or the process restarts. This command is the "something".
 *
 * <h2>Why {@code StoredFile} is refused</h2>
 *
 * Reloading a section re-reads every row in it, and for {@code StoredFile} the rows carry file
 * content. On a populated deployment that is a full-table, full-blob read into heap - which has
 * already crashed this server: an unconditional reload on every change notification, during a
 * burst of uploads, produced {@code OutOfMemoryError} straight out of the JDBC driver's own result
 * buffering. That is exactly the operation this command would perform, on demand, so it is
 * refused with the reason rather than offered with a warning.
 */
public class ReloadCommand implements Command {

    /**
     * The entity types this command will reload, by lowercase name.
     *
     * <p>An explicit allow-list rather than reflective class lookup: a typo should print the valid
     * options, not attempt to reload something arbitrary, and it is what makes the {@code
     * StoredFile} refusal below possible to state precisely.
     */
    private static final Map<String, Class<? extends Serialized>> RELOADABLE = buildReloadable();

    private static Map<String, Class<? extends Serialized>> buildReloadable() {
        final Map<String, Class<? extends Serialized>> types = new LinkedHashMap<>();
        types.put("authuser", AuthUser.class);
        types.put("clouduser", CloudUser.class);
        types.put("folder", Folder.class);
        types.put("storedfileownership", StoredFileOwnership.class);
        types.put("sharedfilegrant", SharedFileGrant.class);
        types.put("sharedfoldergrant", SharedFolderGrant.class);
        return types;
    }

    /** @return {@code "reload"} */
    @Override
    public @NotNull String name() {
        return "reload";
    }

    /** @return {@code "refresh"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("refresh");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Re-read one entity type from the database, discarding this process's cached mirror";
    }

    /**
     * Reloads the named entity type.
     *
     * @param arguments {@code <EntityType>}
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();

        if (arguments.isEmpty()) {
            this.sendHelp(terminal);
            return;
        }

        final String requested = arguments.command(0).toLowerCase(Locale.ROOT);

        if ("storedfile".equals(requested)) {
            terminal.displayApproved("&cRefusing to reload StoredFile&7 - see this command's own documentation.");
            terminal.displayApproved("&7That section holds file content; re-reading it loads the whole corpus into heap");
            terminal.displayApproved("&7and has already crashed this server with an &cOutOfMemoryError&7 once.");
            terminal.displayApproved("&7Reload &fStoredFileOwnership &7instead - it carries every field a listing needs.");
            return;
        }

        final Class<? extends Serialized> type = RELOADABLE.get(requested);
        if (type == null) {
            terminal.displayApproved("Unknown entity type '&b%s&7'.", arguments.command(0));
            this.sendHelp(terminal);
            return;
        }

        final DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();
        try {
            dataFactory.reload(type);
            terminal.displayApproved("Reloaded &b%s &7from the database.", type.getSimpleName());
        } catch (final Exception failed) {
            terminal.displayApproved("&cReload failed&7: %s", failed.getMessage());
        }
    }

    /** Prints the valid entity types. */
    private void sendHelp(final Terminal terminal) {
        terminal.displayApproved("&freload <entityType>");
        terminal.displayApproved("&7Available: &f%s", String.join(", ",
                RELOADABLE.values().stream().map(Class::getSimpleName).toList()));
    }

}
