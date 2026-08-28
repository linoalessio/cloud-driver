package de.lino.cloud.platform.app.api.session;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Windows has no simple CLI equivalent to macOS's {@code security} or Linux's {@code
 * secret-tool} that can actually read a stored secret back out (Credential Manager's
 * {@code cmdkey} can only write/delete, never read). Instead, this uses Windows DPAPI - via
 * PowerShell's {@code ConvertTo-SecureString}/{@code ConvertFrom-SecureString} cmdlets - to
 * encrypt the token with a key tied to the current Windows user account, then stores only the
 * resulting ciphertext in a file under {@code %APPDATA%}. Only this Windows user, on this
 * machine, can ever decrypt it back - a stolen copy of the file alone is useless.
 */
final class WindowsDpapiTokenStore implements TokenStore {

    private final Path storageFile;

    WindowsDpapiTokenStore() {
        final String appData = System.getenv("APPDATA");
        final Path baseDir = appData != null
                ? Path.of(appData, "cloud-driver-desktop")
                : Path.of(System.getProperty("user.home"), "AppData", "Roaming", "cloud-driver-desktop");
        this.storageFile = baseDir.resolve("session.dpapi");
    }

    @Override
    public void save(final String token) throws TokenStoreException {
        final String encrypted = runPowerShell(
                "$plain = $input; $secure = ConvertTo-SecureString -String $plain -AsPlainText -Force; "
                        + "ConvertFrom-SecureString -SecureString $secure",
                token
        );
        try {
            Files.createDirectories(this.storageFile.getParent());
            Files.writeString(this.storageFile, encrypted, StandardCharsets.US_ASCII);
        } catch (final IOException e) {
            throw new TokenStoreException("@WindowsDpapiTokenStore.save: failed to write " + this.storageFile, e);
        }
    }

    @Override
    public Optional<String> load() throws TokenStoreException {
        if (!Files.exists(this.storageFile)) {
            return Optional.empty();
        }
        final String encrypted;
        try {
            encrypted = Files.readString(this.storageFile, StandardCharsets.US_ASCII).strip();
        } catch (final IOException e) {
            throw new TokenStoreException("@WindowsDpapiTokenStore.load: failed to read " + this.storageFile, e);
        }
        if (encrypted.isEmpty()) {
            return Optional.empty();
        }
        final String decrypted = runPowerShell(
                "$enc = $input; $secure = ConvertTo-SecureString -String $enc; "
                        + "[Runtime.InteropServices.Marshal]::PtrToStringAuto("
                        + "[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))",
                encrypted
        );
        return decrypted.isEmpty() ? Optional.empty() : Optional.of(decrypted);
    }

    @Override
    public void clear() throws TokenStoreException {
        try {
            Files.deleteIfExists(this.storageFile);
        } catch (final IOException e) {
            throw new TokenStoreException("@WindowsDpapiTokenStore.clear: failed to delete " + this.storageFile, e);
        }
    }

    /**
     * Runs {@code script} via {@code powershell -NoProfile -Command -}, feeding {@code stdinInput}
     * on stdin (never as a command-line argument - keeps the raw token out of the process list a
     * tool like Task Manager could otherwise observe) and returning trimmed stdout.
     */
    private static String runPowerShell(final String script, final String stdinInput) throws TokenStoreException {
        try {
            final Process process = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", script
            ).start();

            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(stdinInput.getBytes(StandardCharsets.UTF_8));
            }

            final String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            final boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new TokenStoreException("@WindowsDpapiTokenStore: powershell did not respond within 10s");
            }
            if (process.exitValue() != 0) {
                throw new TokenStoreException("@WindowsDpapiTokenStore: powershell failed: " + stderr);
            }
            return stdout.strip();
        } catch (final IOException e) {
            throw new TokenStoreException("@WindowsDpapiTokenStore: failed to run powershell - is it on PATH?", e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TokenStoreException("@WindowsDpapiTokenStore: interrupted while running powershell", e);
        }
    }

}
