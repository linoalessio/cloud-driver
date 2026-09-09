package de.lino.cloud.api.intelligence;

import de.lino.cloud.api.user.ICloudUserService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A near-duplicate group as returned by {@link ICloudUserService#findDuplicateFiles} - {@link
 * DuplicateGroup} after the access-checked, name-resolving pass that turns bare file ids into
 * something a client can actually render.
 *
 * <p>Separate from {@link DuplicateGroup} for the same reason {@link SemanticSearchResult} is
 * separate from {@link SemanticMatch}: the raw form is what an untrusted service returned, this
 * form is what survived re-validation. Keeping them distinct types makes it impossible to hand a
 * client the unchecked one by accident.
 *
 * <p>See {@link DuplicateGroup}'s own Javadoc for what "near-duplicate" does and does not mean -
 * in particular that this is a suggestion for a human, never grounds for automatic deletion.
 *
 * @param files the group's members, always at least two
 * @param similarity the group's weakest pairwise cosine similarity, in {@code [0, 1]}
 */
public record DuplicateFileGroup(@NotNull List<Entry> files, double similarity) {

    /**
     * One member of a {@link DuplicateFileGroup}.
     *
     * @param storedFileId the file's id
     * @param fileName its current display name
     * @param folderId the folder it currently sits in, or {@code null} for the account's root -
     * included because "which of these two identical files is the one I keep?" is almost always
     * answered by where they live, not by what they are called
     */
    public record Entry(@NotNull String storedFileId, @NotNull String fileName, @Nullable String folderId) {
    }
}
