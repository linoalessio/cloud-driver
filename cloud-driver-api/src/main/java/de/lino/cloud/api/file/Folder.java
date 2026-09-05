package de.lino.cloud.api.file;

import de.lino.cloud.api.jwt.rest.Owned;
import de.lino.cloud.api.utility.Asserts;
import de.lino.database.database.entity.Serialized;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A folder a user can organize their {@link StoredFile}s into, the way every other cloud
 * storage system lets a file be filed under a directory rather than only ever sitting in one
 * flat list.
 *
 * <p>A folder does <b>not</b> itself track which {@link StoredFile}s or child {@link Folder}s
 * it contains - keeping a membership list here would reintroduce the exact O(n)
 * full-entity-decrypt-and-rewrite problem {@code StoredFileOwnership} (in {@code
 * cloud-driver-auth}) was built to avoid for file ownership: every single file moved in or out
 * of a large folder would force a full re-encrypt of this entity's entire membership list.
 * Instead, a {@link StoredFile}'s placement is tracked on its own {@code StoredFileOwnership}
 * row (its {@code folderId}), and a child folder simply points back at its own {@link
 * #parentFolderId} - the parent is never touched when a child is created, renamed, moved, or
 * deleted.
 *
 * <p>Implements {@link Owned} - {@link #ownerId} both is this folder's owner and (deliberately,
 * unlike {@code CloudUser}'s {@code authUserId}) serializes under the JSON field name {@code
 * "ownerId"}, so {@code DefaultRestFactory}'s generic owner-spoof protection applies to this
 * type directly.
 *
 * <p>Immutable, the same way {@link StoredFile} is: {@link #renamedTo(String)}/{@link
 * #movedTo(String)} return a new instance with {@link #modifiedAtEpochMillis} refreshed rather
 * than mutating this one in place - a caller (e.g. {@code CloudUserService}) persists the
 * returned copy via {@code DataFactory#update}.
 */
@Getter
@ToString
@EqualsAndHashCode(callSuper = false)
public final class Folder extends Serialized implements Owned {

    /** This folder's unique id, its {@link #primaryKey()}. */
    private final String folderId;

    /** The owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} - also this row's {@link #ownerId()}. */
    private final String ownerId;

    /** This folder's display name. */
    private final String name;

    /** The parent {@link #folderId} this folder sits inside, or {@code null} if it is top-level. */
    @Nullable
    private final String parentFolderId;

    /** When this folder was first created, as epoch milliseconds. */
    private final long createdAtEpochMillis;

    /** When this folder was last renamed or moved, as epoch milliseconds - refreshed by {@link #renamedTo(String)}/{@link #movedTo(String)}. */
    private final long modifiedAtEpochMillis;

    /**
     * When this folder was soft-deleted, or {@code null} if it is not currently in the trash - see
     * {@link #markedDeleted()}/{@link #restored()}. Boxed, same "nullable field = feature not
     * opted into" convention {@link #parentFolderId} already uses.
     */
    @Nullable
    private final Long deletedAtEpochMillis;

    /**
     * This folder's display color, or {@code null} if never explicitly set (a client falls back to
     * its own default, e.g. blue) - the same "opaque string, real enum lives client-side" convention
     * {@code CloudUser#getThemeMode()} already uses, so this layer never has to agree with either
     * client on a fixed palette. Set via {@link #coloredAs(String)}.
     */
    @Nullable
    private final String color;

    /**
     * Creates a fresh folder, stamping {@link #createdAtEpochMillis}/{@link
     * #modifiedAtEpochMillis} with the current time.
     *
     * @param folderId this folder's unique id, its {@link #primaryKey()}
     * @param ownerId the owning user's id
     * @param name this folder's display name
     * @param parentFolderId the parent folder's id, or {@code null} for a top-level folder
     * @throws NullPointerException if {@code folderId}/{@code ownerId}/{@code name} is {@code null}
     */
    public Folder(final String folderId, final String ownerId, final String name, @Nullable final String parentFolderId) {
        this(folderId, ownerId, name, parentFolderId, System.currentTimeMillis(), System.currentTimeMillis());
    }

    /**
     * Same as {@link #Folder(String, String, String, String, long, long, Long)}, leaving {@link
     * #deletedAtEpochMillis} unset ({@code null}) - the shape every pre-existing caller (and every
     * row written before soft delete existed, via Gson deserialization) already uses.
     *
     * @param folderId this folder's unique id, its {@link #primaryKey()}
     * @param ownerId the owning user's id
     * @param name this folder's display name
     * @param parentFolderId the parent folder's id, or {@code null} for a top-level folder
     * @param createdAtEpochMillis when this folder was first created
     * @param modifiedAtEpochMillis when this folder was last renamed or moved
     * @throws NullPointerException if {@code folderId}/{@code ownerId}/{@code name} is {@code null}
     */
    public Folder(final String folderId, final String ownerId, final String name, @Nullable final String parentFolderId,
                   final long createdAtEpochMillis, final long modifiedAtEpochMillis) {
        this(folderId, ownerId, name, parentFolderId, createdAtEpochMillis, modifiedAtEpochMillis, null);
    }

    /**
     * Same as {@link #Folder(String, String, String, String, long, long, Long, String)}, leaving
     * {@link #color} unset ({@code null}) - the shape every caller that predates per-folder color
     * (and every row written before it existed, via Gson deserialization) already uses.
     *
     * @param folderId this folder's unique id, its {@link #primaryKey()}
     * @param ownerId the owning user's id
     * @param name this folder's display name
     * @param parentFolderId the parent folder's id, or {@code null} for a top-level folder
     * @param createdAtEpochMillis when this folder was first created
     * @param modifiedAtEpochMillis when this folder was last renamed or moved
     * @param deletedAtEpochMillis when this folder was soft-deleted, or {@code null} if it is not currently in the trash
     * @throws NullPointerException if {@code folderId}/{@code ownerId}/{@code name} is {@code null}
     */
    public Folder(final String folderId, final String ownerId, final String name, @Nullable final String parentFolderId,
                   final long createdAtEpochMillis, final long modifiedAtEpochMillis, @Nullable final Long deletedAtEpochMillis) {
        this(folderId, ownerId, name, parentFolderId, createdAtEpochMillis, modifiedAtEpochMillis, deletedAtEpochMillis, null);
    }

    /**
     * Full constructor, for re-hydrating a folder with every known field (soft-delete state and
     * color included) - see {@link #renamedTo(String)}/{@link #movedTo(String)}/{@link
     * #markedDeleted()}/{@link #restored()}/{@link #coloredAs(String)}, which are all built on this.
     *
     * @param folderId this folder's unique id, its {@link #primaryKey()}
     * @param ownerId the owning user's id
     * @param name this folder's display name
     * @param parentFolderId the parent folder's id, or {@code null} for a top-level folder
     * @param createdAtEpochMillis when this folder was first created
     * @param modifiedAtEpochMillis when this folder was last renamed or moved
     * @param deletedAtEpochMillis when this folder was soft-deleted, or {@code null} if it is not currently in the trash
     * @param color this folder's display color, or {@code null} if never explicitly set
     * @throws NullPointerException if {@code folderId}/{@code ownerId}/{@code name} is {@code null}
     */
    public Folder(final String folderId, final String ownerId, final String name, @Nullable final String parentFolderId,
                   final long createdAtEpochMillis, final long modifiedAtEpochMillis, @Nullable final Long deletedAtEpochMillis,
                   @Nullable final String color) {
        this.folderId = Asserts.requireNonNull(folderId, "@Folder.init: folderId cannot be null");
        this.ownerId = Asserts.requireNonNull(ownerId, "@Folder.init: ownerId cannot be null");
        this.name = Asserts.requireNonNull(name, "@Folder.init: name cannot be null");
        this.parentFolderId = parentFolderId;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.modifiedAtEpochMillis = modifiedAtEpochMillis;
        this.deletedAtEpochMillis = deletedAtEpochMillis;
        this.color = color;
    }

    /** @return {@code true} if this folder sits inside another folder rather than being top-level */
    public boolean hasParentFolder() {
        return this.parentFolderId != null;
    }

    /** @return {@code true} if this folder is currently soft-deleted (in the trash) */
    public boolean isDeleted() {
        return this.deletedAtEpochMillis != null;
    }

    /**
     * @param newName this folder's new display name
     * @return a copy of this folder with {@link #name} changed to {@code newName} and {@link #modifiedAtEpochMillis} refreshed
     */
    @NotNull
    public Folder renamedTo(final String newName) {
        return new Folder(this.folderId, this.ownerId, newName, this.parentFolderId,
                this.createdAtEpochMillis, System.currentTimeMillis(), this.deletedAtEpochMillis, this.color);
    }

    /**
     * @param newParentFolderId the folder's new parent, or {@code null} to move it to the top level
     * @return a copy of this folder with {@link #parentFolderId} changed to {@code newParentFolderId} and {@link #modifiedAtEpochMillis} refreshed
     */
    @NotNull
    public Folder movedTo(@Nullable final String newParentFolderId) {
        return new Folder(this.folderId, this.ownerId, this.name, newParentFolderId,
                this.createdAtEpochMillis, System.currentTimeMillis(), this.deletedAtEpochMillis, this.color);
    }

    /**
     * @param newColor this folder's new display color, or {@code null} to clear it back to "unset"
     * @return a copy of this folder with {@link #color} changed to {@code newColor} and {@link #modifiedAtEpochMillis} refreshed
     */
    @NotNull
    public Folder coloredAs(@Nullable final String newColor) {
        return new Folder(this.folderId, this.ownerId, this.name, this.parentFolderId,
                this.createdAtEpochMillis, System.currentTimeMillis(), this.deletedAtEpochMillis, newColor);
    }

    /** @return a copy of this folder, soft-deleted as of now */
    @NotNull
    public Folder markedDeleted() {
        return new Folder(this.folderId, this.ownerId, this.name, this.parentFolderId,
                this.createdAtEpochMillis, System.currentTimeMillis(), System.currentTimeMillis(), this.color);
    }

    /** @return a copy of this folder, restored out of the trash */
    @NotNull
    public Folder restored() {
        return new Folder(this.folderId, this.ownerId, this.name, this.parentFolderId,
                this.createdAtEpochMillis, System.currentTimeMillis(), null, this.color);
    }

    /** @return this entity's primary key, a single-element list containing {@link #folderId} */
    @NotNull
    @Override
    public List<String> keysOf() {
        return List.of(this.folderId);
    }

    /** @return {@link #ownerId} */
    @NotNull
    @Override
    public String ownerId() {
        return this.ownerId;
    }

}
