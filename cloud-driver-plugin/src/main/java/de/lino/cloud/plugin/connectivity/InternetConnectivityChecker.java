package de.lino.cloud.plugin.connectivity;

import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.utility.Asserts;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;

/**
 * {@link ConnectivityChecker} that probes well-known public DNS resolvers
 * ({@link #DEFAULT_PROBES}) via a short-lived TCP connection, rather than
 * the configured database, so a database outage isn't mistaken for a
 * network outage. Reports available on the first probe that connects.
 */
public final class InternetConnectivityChecker implements ConnectivityChecker {

    /** The well-known public DNS resolvers (Cloudflare, Google) probed by default, in order. */
    private static final List<InetSocketAddress> DEFAULT_PROBES = List.of(
            new InetSocketAddress("1.1.1.1", 53),
            new InetSocketAddress("8.8.8.8", 53)
    );
    /** The per-probe connection timeout used by the no-arg constructor. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    /** The addresses probed by {@link #isAvailable()}, tried in order until one accepts a connection. */
    private final List<InetSocketAddress> probes;
    /** The maximum time to wait for each probe's connection attempt. */
    private final Duration timeout;

    /**
     * Constructs a checker probing {@link #DEFAULT_PROBES} with a {@link #DEFAULT_TIMEOUT} of 2 seconds each.
     */
    public InternetConnectivityChecker() {
        this(DEFAULT_PROBES, DEFAULT_TIMEOUT);
    }

    /**
     * @param probes the addresses to probe, tried in order until one succeeds
     * @param timeout the maximum time to wait for each probe's connection attempt
     * @throws NullPointerException if {@code probes} or {@code timeout} is {@code null}
     */
    public InternetConnectivityChecker(@NotNull final List<InetSocketAddress> probes, @NotNull final Duration timeout) {
        this.probes = Asserts.requireNonNull(probes, "@InternetConnectivityChecker: probes cannot be null");
        this.timeout = Asserts.requireNonNull(timeout, "@InternetConnectivityChecker: timeout cannot be null");
    }

    /**
     * Tries each of {@link #probes} in order, opening a short-lived {@link Socket}
     * to it with a timeout of {@link #timeout}; reports available on the first
     * probe that accepts a connection.
     *
     * @return {@code true} if any probe accepted a connection within {@link #timeout}, {@code false} if all did not
     */
    @Override
    public boolean isAvailable() {
        for (final InetSocketAddress probe : this.probes) {
            try (final Socket socket = new Socket()) {
                socket.connect(probe, (int) this.timeout.toMillis());
                return true;
            } catch (final IOException unreachable) {
                // try the next probe - a single unreachable host does not mean connectivity is down
            }
        }
        return false;
    }

}
