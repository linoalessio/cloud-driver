package de.lino.cloud.extensions;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.utility.Constraints;
import de.lino.database.database.auth.Credentials;

public class CloudBackupExtension extends Extension {

    private DatabaseBackupScheduler backupScheduler;

    @Override
    public void onLoading() {

        this.backupScheduler = new DatabaseBackupScheduler(
                Credentials.of(Constraints.CONFIGURATION_PATH.resolve("postgres-database.json")).get()
                , Constraints.CONFIGURATION_PATH.resolve("backup")
        );

    }

    @Override
    public void onRunning(String[] args) {

        this.backupScheduler.start();

    }

    @Override
    public void onEnding() {

    }

    @Override
    public void onException(RuntimeException reason) {

    }

}
