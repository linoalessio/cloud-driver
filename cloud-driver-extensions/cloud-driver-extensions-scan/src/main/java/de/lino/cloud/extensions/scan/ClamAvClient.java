package de.lino.cloud.extensions.scan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal client for {@code clamd}'s {@code INSTREAM} wire protocol - deliberately hand-rolled
 * (see this module's own {@code pom.xml} comment for why no third-party ClamAV client library was
 * added). Implements exactly what {@code INSTREAM} needs, following the protocol as documented by
 * ClamAV itself:
 *
 * <ol>
 *   <li>Send the literal command {@code "zINSTREAM\0"} - the {@code z} prefix selects a
 *       NUL-terminated response, avoiding any ambiguity with {@code \n} bytes that might appear
 *       inside a scanned file's own content.</li>
 *   <li>Stream the file as a sequence of chunks, each a 4-byte big-endian length prefix followed
 *       by that many raw bytes.</li>
 *   <li>Terminate with a zero-length chunk (4 zero bytes, no data).</li>
 *   <li>Read a single NUL-terminated reply - {@code "stream: OK"} (clean), {@code "stream:
 *       &lt;name&gt; FOUND"} (flagged), or an {@code "...ERROR"} suffix (scan failed, not a
 *       verdict - see {@link ClamAvScanException}).</li>
 * </ol>
 *
 * <p><b>Not verified against a real running {@code clamd} instance</b> - this sandboxed
 * environment has no ClamAV daemon available to test against, so this implementation follows the
 * documented protocol precisely but hasn't been exercised end-to-end the way this codebase's own
 * "confirmed by running the real thing" bar normally requires (see e.g. {@code
 * cloud-driver-platforms-desktop}'s own icon-trimming verification). Flagged explicitly rather
 * than silently claimed as tested - verify against a real {@code clamd} (e.g. {@code docker run
 * clamav/clamav}) before relying on this in production.
 */
final class ClamAvClient {

    /** Matches a clean result - {@code "stream: OK"}. */
    private static final Pattern CLEAN_PATTERN = Pattern.compile("^stream:\\s*OK\\s*$");

    /** Matches a flagged result, capturing the malware name - {@code "stream: <name> FOUND"}. */
    private static final Pattern FOUND_PATTERN = Pattern.compile("^stream:\\s*(.+?)\\s+FOUND\\s*$");

    /** Chunk size used when streaming content to {@code clamd} - an arbitrary, reasonable buffer size, not a protocol requirement. */
    private static final int CHUNK_SIZE = 8192;

    private final String host;
    private final int port;
    private final Duration timeout;

    ClamAvClient(final String host, final int port, final Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    /**
     * Scans {@code content} via {@code clamd}'s {@code INSTREAM} command.
     *
     * @param content the raw bytes to scan
     * @return the parsed verdict
     * @throws IOException if the connection itself fails (refused, timed out, reset, ...)
     * @throws ClamAvScanException if {@code clamd} accepted the connection but reported a scan-level error (e.g. its own size limit exceeded)
     */
    ClamAvScanResult scan(final byte[] content) throws IOException, ClamAvScanException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(this.host, this.port), (int) this.timeout.toMillis());
            socket.setSoTimeout((int) this.timeout.toMillis());

            final OutputStream out = socket.getOutputStream();
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));

            int offset = 0;
            while (offset < content.length) {
                final int length = Math.min(CHUNK_SIZE, content.length - offset);
                writeChunk(out, content, offset, length);
                offset += length;
            }
            out.write(new byte[]{0, 0, 0, 0}); // zero-length chunk terminates the stream
            out.flush();

            final String response = readNulTerminatedResponse(socket.getInputStream());
            return parseResponse(response);
        }
    }

    private static void writeChunk(final OutputStream out, final byte[] content, final int offset, final int length) throws IOException {
        out.write(new byte[]{
                (byte) (length >>> 24), (byte) (length >>> 16), (byte) (length >>> 8), (byte) length
        });
        out.write(content, offset, length);
    }

    private static String readNulTerminatedResponse(final InputStream in) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) != -1 && b != 0) {
            buffer.write(b);
        }
        return buffer.toString(StandardCharsets.UTF_8).trim();
    }

    private static ClamAvScanResult parseResponse(final String response) throws ClamAvScanException {
        if (CLEAN_PATTERN.matcher(response).matches()) {
            return new ClamAvScanResult(true, null);
        }
        final Matcher foundMatcher = FOUND_PATTERN.matcher(response);
        if (foundMatcher.matches()) {
            return new ClamAvScanResult(false, foundMatcher.group(1));
        }
        throw new ClamAvScanException("Unexpected clamd response: " + response);
    }

}
