package de.lino.cloud.api.icloud;

/**
 * One file or directory in an iCloud Drive tree, as returned by {@link IcloudBridge#listTree}.
 *
 * @param path the entry's full path within iCloud Drive, {@code /}-separated, relative to the Drive root
 * @param directory {@code true} if this entry is a directory (its own content is every other entry
 *                  whose {@code path} is directly nested under it), {@code false} if it's a file
 * @param sizeBytes the file's size in bytes; meaningless (always {@code 0}) for a directory entry
 */
public record IcloudTreeEntry(String path, boolean directory, long sizeBytes) {
}
