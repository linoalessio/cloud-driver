package de.lino.cloud.api.intelligence;

import de.lino.cloud.api.file.StoredFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One file's state as handed to {@link IntelligenceService#indexAsync} for embedding - the Java
 * side deliberately performs <em>no</em> interpretation of {@link #content()} beyond passing it
 * along: deciding what is embeddable (decoding text, running OCR, computing a CLIP image
 * embedding, or falling back to a name-only embedding) is entirely the Python service's own
 * concern, keeping the Java bridge a thin pipe in exactly the way {@code ClamAvClient} already is
 * for {@code clamd}.
 *
 * @param authUserId the owning account - passed to the Python service only as a <b>hint</b> for its
 * own bookkeeping, <b>never</b> as an authorization decision (see {@link IntelligenceService}'s own
 * Javadoc, §6 of the handoff document: the Python service is never permitted to decide what a user
 * may see)
 * @param storedFileId the {@link StoredFile#fileId()} being indexed - the vector store's own key
 * @param fileName the file's current display name, embedded alongside its content (a name alone is
 * frequently the only usable signal for a binary format nothing can extract text from)
 * @param contentType the file's {@link StoredFile#contentType()}, letting the Python service choose
 * how (or whether) to interpret {@link #content()}
 * @param content the file's raw, uncompressed bytes, or {@code null} if this server never held them
 * at all (a direct-transfer/presigned upload - see {@code CloudUserService#completePresignedUpload}),
 * in which case only {@link #fileName()} is embeddable. Not defensively copied: this record is
 * always constructed from bytes the caller is about to discard, and copying a
 * potentially-very-large array purely to hand it to one consumer would be pure waste - <b>a caller
 * must not mutate the array after passing it here.</b>
 */
public record IntelligenceDocument(@NotNull String authUserId, @NotNull String storedFileId,
                                   @NotNull String fileName, @NotNull String contentType,
                                   @Nullable byte[] content) {
}
