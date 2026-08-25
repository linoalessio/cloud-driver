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
 * {@link ConnectivityChecker} that answers by opening a short-lived TCP
 * connection to one of a handful of well-known, highly available public DNS
 * resolvers ({@link #DEFAULT_PROBES}) - not the configured database itself,
 * since a database connection failure does not by itself distinguish "no
 * internet connection" from any other outage (wrong credentials, database
 * down, firewall rule, ...). {@link #isAvailable()} tries each probe in turn
 * and returns {@code true} on the first that accepts a connection within
 * {@link #timeout}; only if every probe fails does it report unavailable.
 */
public final class InternetConnectivityChecker implements ConnectivityChecker {

    private static final List<InetSocketAddress> DEFAULT_PROBES = List.of(
            new InetSocketAddress("1.1.1.1", 53),
            new InetSocketAddress("8.8.8.8", 53)
    );
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);

    private final List<InetSocketAddress> probes;
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
        this.probes = Asserts.assertNotNull(probes, "@InternetConnectivityChecker: probes cannot be null");
        this.timeout = Asserts.assertNotNull(timeout, "@InternetConnectivityChecker: timeout cannot be null");
    }

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
