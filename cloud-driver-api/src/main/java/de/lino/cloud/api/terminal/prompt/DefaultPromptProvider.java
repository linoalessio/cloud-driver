package de.lino.cloud.api.terminal.prompt;

import de.lino.cloud.api.terminal.Terminal;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * The {@link PromptProvider} {@link Terminal} falls back to when none is given explicitly:
 * a {@code cloud-driver@<random>} prompt with a fresh random suffix on every call.
 */
public final class DefaultPromptProvider implements PromptProvider {

    /**
     * @return a {@code cloud-driver@<random>} prompt with a fresh random suffix
     */
    @Override
    public @NotNull String prompt() {
        return String.format("%scloud-driver%s@%s%s %s❯ %s", "&b", "&8", "&b", UUID.randomUUID().toString().substring(0, 7), "&8", "&7");
    }

}
