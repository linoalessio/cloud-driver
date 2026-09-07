package de.lino.cloud.extensions.scan;

/**
 * The parsed verdict from one {@link ClamAvClient#scan} call.
 *
 * @param clean {@code true} if {@code clamd} reported no match ({@code "stream: OK"})
 * @param malwareName the signature name {@code clamd} matched against, or {@code null} if {@link #clean}
 */
record ClamAvScanResult(boolean clean, String malwareName) {
}
