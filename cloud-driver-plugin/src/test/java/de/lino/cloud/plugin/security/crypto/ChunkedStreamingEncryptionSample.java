package de.lino.cloud.plugin.security.crypto;

import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.crypto.CryptoAlgorithm;
import de.lino.cloud.api.security.crypto.StreamingAeadEncryptionService;
import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.plugin.s3storage.StoredFileContentChannel;
import de.lino.cloud.plugin.security.envelope.EnvelopeEncryptionService;
import de.lino.cloud.plugin.security.keys.develop.DataEncryptionKeyGenerator;
import de.lino.cloud.plugin.security.keys.develop.InMemoryKeyEncryptionService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Standalone, runnable worked example (not an {@code mvn test} target - see "Testing" in {@code
 * CLAUDE.md} for this repo's "sample under {@code src/test}, run {@code main} directly"
 * convention) exercising the chunked streaming-encryption path end to end - {@link
 * ChunkedAesGcmStreamingService} directly, then {@link EnvelopeEncryptionService}'s streaming
 * entry points through {@link StoredFileContentChannel} - against an {@link
 * InMemoryKeyEncryptionService} (no AWS/KMS/S3 needed), printing pass/fail per check to stdout
 * and exiting non-zero on any failure.
 *
 * <p>Covers the properties chunked streaming encryption must hold, and the failure modes
 * chunking must stay safe against:
 *
 * <ul>
 *     <li>multi-chunk round-trip correctness (partial, exact-multiple, and empty plaintexts),
 *     both the pull- and push-based encryptors</li>
 *     <li>the declared {@code encryptedLength}/{@code contentLength} matching the bytes actually produced</li>
 *     <li>truncating the stored object at <em>every</em> byte boundary fails decryption - never a
 *     silently shorter file</li>
 *     <li>a flipped ciphertext bit, a wrong file id, and chunk-frame reordering all fail
 *     authentication</li>
 *     <li>the one-shot (schema version 1) layout still round-trips through the same receive
 *     methods - the version dispatch keeps legacy objects readable</li>
 * </ul>
 */
public final class ChunkedStreamingEncryptionSample {

    /** Chunk size used throughout the sample - small, so a few hundred KiB of plaintext already spans many chunks. */
    private static final int CHUNK_SIZE_BYTES = 64 * 1024;

    /** Tracks whether every check so far passed; {@link #main} exits non-zero if any failed. */
    private static boolean allPassed = true;

    /** Not instantiable; this sample is driven entirely through its static {@link #main}. */
    private ChunkedStreamingEncryptionSample() {
    }

    /**
     * Runs every check described in this class's own Javadoc and reports pass/fail to stdout.
     *
     * @param args unused
     * @throws Exception on any unexpected failure - the sample makes no attempt to continue past one
     */
    public static void main(final String[] args) throws Exception {

        final StreamingAeadEncryptionService streamingService =
                new ChunkedAesGcmStreamingService(CryptoAlgorithm.AES_256_GCM, CHUNK_SIZE_BYTES);
        final EnvelopeEncryptionService envelopeService = new EnvelopeEncryptionService(
                new DataEncryptionKeyGenerator(),
                new AesGcmEncryptionService(),
                streamingService,
                new InMemoryKeyEncryptionService(),
                CryptoAlgorithm.AES_256_GCM
        );
        final StoredFileContentChannel channel = new StoredFileContentChannel(envelopeService);
        final SecureRandom random = new SecureRandom();

        // --- round trips: partial final chunk, exact chunk multiple, single partial chunk, empty ---
        for (final int plaintextLength : new int[]{200_000, 3 * CHUNK_SIZE_BYTES, 1_000, 0}) {
            final byte[] plaintext = new byte[plaintextLength];
            random.nextBytes(plaintext);
            final String fileId = "sample-file-" + plaintextLength;

            final StoredFileContentChannel.StreamingPayload payload =
                    channel.sendStream(fileId, new ByteArrayInputStream(plaintext), plaintext.length);
            final byte[] stored = payload.content().readAllBytes();

            check("declared contentLength matches produced bytes (len=" + plaintextLength + ")",
                    stored.length == payload.contentLength());
            check("streaming round trip (len=" + plaintextLength + ")",
                    Arrays.equals(plaintext, channel.receiveFully(fileId, new ByteArrayInputStream(stored))));
            check("byte[] receive dispatches to streaming layout (len=" + plaintextLength + ")",
                    Arrays.equals(plaintext, channel.receive(fileId, stored)));
        }

        // --- push-based encryptor produces the same verifiable stream shape ---
        {
            final byte[] plaintext = new byte[2 * CHUNK_SIZE_BYTES + 12_345];
            random.nextBytes(plaintext);
            final var key = new DataEncryptionKeyGenerator().generate(CryptoAlgorithm.AES_256_GCM);
            final byte[] prefix = "sample-prefix".getBytes();

            final ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (OutputStream encrypting = streamingService.encryptingOutputStream(sink, key.asSecretKey(), prefix)) {
                // Deliberately awkward write sizes, so chunk boundaries never align with writes.
                int position = 0;
                while (position < plaintext.length) {
                    final int step = Math.min(30_000, plaintext.length - position);
                    encrypting.write(plaintext, position, step);
                    position += step;
                }
            }
            check("push-based encryptedLength matches produced bytes",
                    sink.size() == streamingService.encryptedLength(plaintext.length));
            try (InputStream decrypting = streamingService.decryptingInputStream(
                    new ByteArrayInputStream(sink.toByteArray()), key.asSecretKey(), prefix)) {
                check("push-based encrypt / pull-based decrypt round trip",
                        Arrays.equals(plaintext, decrypting.readAllBytes()));
            }
        }

        // --- tampering must always fail, never yield a shorter/altered "valid" file ---
        {
            final byte[] plaintext = new byte[2 * CHUNK_SIZE_BYTES + 7_777];
            random.nextBytes(plaintext);
            final String fileId = "sample-tamper-file";
            final byte[] stored = channel
                    .sendStream(fileId, new ByteArrayInputStream(plaintext), plaintext.length)
                    .content().readAllBytes();

            boolean everyTruncationRejected = true;
            for (int keep = 0; keep < stored.length; keep++) {
                try {
                    channel.receiveFully(fileId, new ByteArrayInputStream(Arrays.copyOf(stored, keep)));
                    everyTruncationRejected = false;
                    System.out.println("  truncation to " + keep + " bytes was NOT rejected");
                    break;
                } catch (final AuthenticationFailedException | ObjectStorageException expected) {
                    // fail-closed, as required
                }
            }
            check("every possible truncation (0.." + (stored.length - 1) + " bytes kept) is rejected", everyTruncationRejected);

            final byte[] flipped = stored.clone();
            flipped[stored.length - 20] ^= 0x01;
            check("flipped ciphertext bit is rejected", rejects(channel, fileId, flipped));

            check("wrong file id is rejected", rejects(channel, "some-other-file", stored));

            final byte[] extended = Arrays.copyOf(stored, stored.length + 1);
            check("trailing data after the final chunk is rejected", rejects(channel, fileId, extended));
        }

        // --- chunk-frame reordering must fail (crypto layer, where the frame offsets are known) ---
        {
            final byte[] plaintext = new byte[2 * CHUNK_SIZE_BYTES + 5_000];
            random.nextBytes(plaintext);
            final var key = new DataEncryptionKeyGenerator().generate(CryptoAlgorithm.AES_256_GCM);
            final byte[] prefix = "sample-reorder-prefix".getBytes();
            final byte[] ciphertext;
            try (InputStream encrypting = streamingService.encryptingInputStream(
                    new ByteArrayInputStream(plaintext), key.asSecretKey(), prefix)) {
                ciphertext = encrypting.readAllBytes();
            }

            // Layout per ChunkedAesGcmStreamingService's Javadoc: a (nonceLength - 8)-byte nonce
            // base, then frames of [1 flags][4 length][chunk + 16-byte tag] - the two full chunks
            // are equal-sized, so swapping their frames is a pure reordering.
            final int baseNonceLength = CryptoAlgorithm.AES_256_GCM.nonceLengthBytes() - Long.BYTES;
            final int frameLength = 1 + Integer.BYTES + CHUNK_SIZE_BYTES + CryptoAlgorithm.AES_256_GCM.tagLengthBits() / Byte.SIZE;
            final byte[] reordered = ciphertext.clone();
            System.arraycopy(ciphertext, baseNonceLength + frameLength, reordered, baseNonceLength, frameLength);
            System.arraycopy(ciphertext, baseNonceLength, reordered, baseNonceLength + frameLength, frameLength);

            boolean rejected;
            try (InputStream decrypting = streamingService.decryptingInputStream(
                    new ByteArrayInputStream(reordered), key.asSecretKey(), prefix)) {
                decrypting.readAllBytes();
                rejected = false;
            } catch (final AuthenticationFailedException | java.io.IOException expected) {
                rejected = true;
            }
            check("swapped chunk frames are rejected", rejected);
        }

        // --- legacy one-shot layout still round-trips through the same receive methods ---
        {
            final byte[] plaintext = new byte[50_000];
            random.nextBytes(plaintext);
            final String fileId = "sample-legacy-file";
            final byte[] storedV1 = channel.send(fileId, plaintext);

            check("one-shot layout round trip via receive(byte[])",
                    Arrays.equals(plaintext, channel.receive(fileId, storedV1)));
            check("one-shot layout round trip via receiveFully(stream) dispatch",
                    Arrays.equals(plaintext, channel.receiveFully(fileId, new ByteArrayInputStream(storedV1))));
        }

        System.out.println(allPassed ? "ALL CHECKS PASSED" : "CHECKS FAILED");
        if (!allPassed) {
            System.exit(1);
        }
    }

    /** Whether decrypting {@code stored} for {@code fileId} is rejected with an authentication/format failure. */
    private static boolean rejects(final StoredFileContentChannel channel, final String fileId, final byte[] stored) throws Exception {
        try {
            channel.receiveFully(fileId, new ByteArrayInputStream(stored));
            return false;
        } catch (final AuthenticationFailedException | ObjectStorageException expected) {
            return true;
        }
    }

    /** Prints one check's pass/fail line and folds the result into {@link #allPassed}. */
    private static void check(final String description, final boolean passed) {
        System.out.println((passed ? "PASS" : "FAIL") + ": " + description);
        allPassed &= passed;
    }
}
