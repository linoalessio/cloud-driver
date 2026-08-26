package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.command.Command;
import de.lino.cloud.api.utility.Constraints;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Displays uptime, uploaded file count, and total storage used. */
public class AboutCommand implements Command {

    /** @return {@code "about"} */
    @Override
    public @NotNull String name() {
        return "about";
    }

    /** @return {@code "ab"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("ab");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Basic statistic information about the cloud driver";
    }

    /**
     * Prints uptime, uploaded file count, and used storage.
     *
     * @param args unused
     */
    @Override
    public void execute(@NotNull String[] args) {

        final Terminal terminal = this.terminal();
        final FileFactory fileFactory = CloudDriver.getInstance().getFileFactory();

        final String cloudRunningFor = Constraints.resolveMilliSecondsToUnit(System.currentTimeMillis() - Constraints.CLOUD_START_TIME_STAMP.get());
        final String usedStorage = Constraints.resolveBytesToUnit(fileFactory.getEntitiesAsync().join().stream().mapToLong(StoredFile::sizeBytes).sum());
        final String totalFiles = String.valueOf(fileFactory.getEntitiesAsync().join().size());

        terminal.displayApproved("Cloud running for: &b" + cloudRunningFor);
        terminal.displayApproved("Uploaded files: &b" + totalFiles);
        terminal.displayApproved("Used storage: &b" + usedStorage);

    }

}
