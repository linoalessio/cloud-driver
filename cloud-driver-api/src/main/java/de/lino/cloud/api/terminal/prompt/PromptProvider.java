package de.lino.cloud.api.terminal.prompt;

import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.ansi.AnsiColors;
import org.jetbrains.annotations.NotNull;

/**
 * Builds the prompt string a {@link Terminal} displays in front of the cursor, using {@code
 * &x} legacy ansi codes - {@link Terminal#updatePrompt(String)} translates it, so
 * implementations never call {@link AnsiColors#translate(String)} themselves.
 */
public interface PromptProvider {

    /**
     * @return the prompt to display, using {@code &x} legacy ansi codes
     */
    @NotNull String prompt();

}
