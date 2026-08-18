package de.lino.cloud.api.utility;

import de.lino.cloud.api.CloudAPI;

import java.util.function.Supplier;

/**
 * Shared null-validation helpers for parameters and shared state across
 * {@code cloud-driver} - the codebase's alternative to scattering {@code
 * Objects.requireNonNull} calls throughout. Each {@code assertNotNull}
 * overload returns {@code object} unchanged so a check can be inlined at the
 * point of use (e.g. {@code this.field = Asserts.assertNotNull(param, "...")}).
 */
public final class Asserts {

    /**
     * Not instantiable; all functionality is exposed through static methods.
     */
    private Asserts() {}

    /**
     * Returns {@code object} if it is not {@code null}.
     *
     * @param object the value to check
     * @param <T> the type of {@code object}
     * @return {@code object}, unchanged
     * @throws NullPointerException if {@code object} is {@code null}
     */
    public static <T> T assertNotNull(final T object) {
        if (object == null) throw new NullPointerException();
        return object;
    }

    /**
     * Returns {@code object} if it is not {@code null}.
     *
     * @param object the value to check
     * @param message the message the thrown exception carries if {@code object} is {@code null}
     * @param <T> the type of {@code object}
     * @return {@code object}, unchanged
     * @throws NullPointerException if {@code object} is {@code null}
     */
    public static <T> T assertNotNull(final T object, final String message) {
        if (object == null) throw new NullPointerException(message);
        return object;
    }

    /**
     * Returns {@code object} if it is not {@code null}. {@code message} is
     * only evaluated when {@code object} is {@code null}, so use this
     * overload over {@link #assertNotNull(Object, String)} when building the
     * message is expensive.
     *
     * @param object the value to check
     * @param message supplies the message the thrown exception carries if {@code object} is {@code null}
     * @param <T> the type of {@code object}
     * @return {@code object}, unchanged
     * @throws NullPointerException if {@code object} is {@code null}
     */
    public static <T> T assertNotNull(final T object, final Supplier<String> message) {
        if (object == null) throw new NullPointerException(message.get());
        return object;
    }

    /**
     * Returns {@code object} if it is not {@code null}. A dedicated overload
     * for {@link CloudAPI} - typically wrapping {@link CloudAPI#getInstance()}
     * - so a missing installation fails with a message pointing at {@code
     * DefaultCloudAPI.setInstance} instead of a bare {@link
     * NullPointerException}.
     *
     * @param object the {@link CloudAPI} instance to check, e.g. {@code CloudAPI.getInstance()}
     * @return {@code object}, unchanged
     * @throws NullPointerException if {@code object} is {@code null}, i.e. no {@link CloudAPI} implementation has been installed yet
     */
    public static CloudAPI assertNotNull(final CloudAPI object) {

        if (object == null) {
            throw new NullPointerException(
                    "@Asserts.assertNotNull: no CloudAPI implementation is installed yet - install one "
                            + "(e.g. via DefaultCloudAPI.setInstance) before using it"
            );
        }

        return object;
    }

}
