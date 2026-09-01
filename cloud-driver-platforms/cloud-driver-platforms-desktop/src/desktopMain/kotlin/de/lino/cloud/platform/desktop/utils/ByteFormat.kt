package de.lino.cloud.platform.desktop.utils

private val BYTE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

/**
 * Client-side port of the server's `Constraints#resolveBytesToUnit` (`cloud-driver-api`, package
 * `de.lino.cloud.api.utility`) - this module deliberately never depends on `cloud-driver-api`
 * (see CLAUDE.md's "client must never see the database" boundary), so this small, pure formatting
 * routine is reimplemented here identically rather than imported.
 *
 * Formats [bytes] in its largest whole unit, e.g. `2048` -> `"2.00 KB"`.
 */
fun formatBytes(bytes: Long): String {
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < BYTE_UNITS.size - 1) {
        value /= 1024
        unit++
    }
    val formatted = if (unit == 0) value.toLong().toString() else String.format("%.2f", value)
    return "$formatted ${BYTE_UNITS[unit]}"
}
