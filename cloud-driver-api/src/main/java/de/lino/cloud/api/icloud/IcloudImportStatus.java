package de.lino.cloud.api.icloud;

/**
 * The lifecycle states of one on-demand iCloud import job, as tracked by {@link
 * IcloudImportHandle#status()}.
 */
public enum IcloudImportStatus {

    /** {@link IcloudImportService#startImport} has run but Apple demanded a two-factor code; waiting on {@link IcloudImportService#confirmTwoFactor}. */
    AWAITING_TWO_FACTOR,

    /** Authenticated; walking the iCloud Drive tree and uploading files. */
    RUNNING,

    /** Every file was imported successfully. */
    SUCCEEDED,

    /** The job failed - see {@link IcloudImportHandle#errorMessage()}. */
    FAILED

}
