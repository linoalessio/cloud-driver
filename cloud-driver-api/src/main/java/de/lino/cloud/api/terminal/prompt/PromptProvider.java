package de.lino.cloud.api.terminal.prompt;

import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.color.AnsiColors;
import org.jetbrains.annotations.NotNull;

/**
 * Builds the prompt string a {@link Terminal} displays in front of the cursor.
 *
 * <p>Implementations return a raw string using {@link AnsiColors}' {@code &x} legacy color
 * codes - {@link Terminal#updatePrompt(String)} translates it before handing it to the
 * underlying {@code jline} reader, so a {@link PromptProvider} never has to call
 * {@link AnsiColors#translate(String)} itself.
 */
public interface PromptProvider {

    /**
     * @return the prompt to display, using {@code &x} legacy color codes
     */
    @NotNull String prompt();

}
