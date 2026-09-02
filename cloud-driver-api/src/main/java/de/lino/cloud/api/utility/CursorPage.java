package de.lino.cloud.api.utility;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One page of a cursor-paginated listing: {@code items} plus {@code nextCursor} (the key to pass
 * back as the next call's cursor, or {@code null} once nothing more remains). Generic and
 * transport-agnostic - {@code de.lino.cloud.plugin.factory.DefaultRestFactory} serializes this
 * directly as the {@code GET /files}/{@code GET /folders} response envelope once a caller opts
 * into pagination via {@code ?limit=}; {@code de.lino.cloud.platform.rest.api.dto.Dtos} mirrors
 * the same shape client-side.
 *
 * <p>Unlike {@code cloud-driver-extensions-backup}'s {@code DatabaseBackupScheduler}, which pages
 * a table directly at the SQL level ({@code WHERE id > ? ORDER BY id LIMIT ?}, so a page never
 * costs more than its own row count to produce), the {@code ICloudUserService} listings this
 * backs (file/folder listings scoped to one owner) have no such SQL-level cursor available: their
 * rows are envelope-encrypted and owner-scoped only after a full {@code DataFactory#getEntities}
 * scan and in-memory decrypt/filter (see {@code StoredFileOwnership}'s own documented full-scan
 * trade-off). This type therefore only bounds the size of one <em>response</em> - the underlying
 * per-request database/decrypt cost stays O(total owned rows) regardless of {@code limit}, a real
 * limitation flagged here rather than hidden, sharing its eventual fix with that same trade-off
 * (a real indexed query, which doesn't exist today).
 *
 * @param items      up to the requested page size, in the listing's stable sort order
 * @param nextCursor the key to pass back as the next page's {@code cursor}, or {@code null} if this was the last page
 * @param <T>        the element type being paginated
 */
public record CursorPage<T>(@NotNull List<T> items, @Nullable String nextCursor) {
}
