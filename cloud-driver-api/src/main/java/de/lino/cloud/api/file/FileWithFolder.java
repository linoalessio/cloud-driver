package de.lino.cloud.api.file;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Pairs a {@link StoredFile} with the {@link Folder#getFolderId()} it currently sits in -
 * {@code null} for a file at the root, not inside any folder.
 *
 * <p>{@link StoredFile} itself carries no folder reference (see {@link Folder}'s Javadoc for
 * why placement lives on {@code StoredFileOwnership} instead, in {@code cloud-driver-auth});
 * this is the shape {@code ICloudUserService#listFilesWithFolder} returns so a caller can
 * render a file tree without a second lookup per file.
 *
 * @param file the file itself
 * @param folderId the {@link Folder#getFolderId()} this file is placed in, or {@code null} for the root
 */
public record FileWithFolder(@NotNull StoredFile file, @Nullable String folderId) {
}
