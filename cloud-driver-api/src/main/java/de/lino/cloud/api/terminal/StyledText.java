package de.lino.cloud.api.terminal;

import de.lino.cloud.api.utility.Asserts;
import lombok.NonNull;

import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable text paired with an optional {@link TerminalColor} and any
 * number of {@link TerminalStyle}s - the unit every {@link Terminal} command
 * result and prompt is expressed in, and a pure value/formatting object like
 * {@link de.lino.cloud.api.file.meta.FileChecksum}: no I/O, no terminal
 * capability detection, just data plus a rendering method a concrete {@link
 * Terminal} implementation calls when it actually writes to a real sink.
 *
 * <p>Every {@code with}-style method ({@link #color}, {@link #style})
 * returns a new instance rather than mutating this one, the same immutable,
 * fluent shape used throughout {@code cloud-driver-api}'s value objects.
 */
public final class StyledText {

    private static final String RESET = "\u001B[0m";

    private final String text;
    private final TerminalColor color;
    private final Set<TerminalStyle> styles;

    private StyledText(final String text, final TerminalColor color, final Set<TerminalStyle> styles) {
        this.text = text;
        this.color = color;
        this.styles = styles;
    }

    /**
     * @param text the plain text to wrap, uncolored and unstyled
     * @return a new {@link StyledText} carrying {@code text} with no color or style
     * @throws NullPointerException if {@code text} is {@code null}
     */
    @NonNull
    public static StyledText of(@NonNull final String text) {
        return new StyledText(Asserts.assertNotNull(text, "@StyledText.of: text cannot be null"), null, EnumSet.noneOf(TerminalStyle.class));
    }

    /**
     * @param color the foreground color to render this text with
     * @return a new {@link StyledText} identical to this one, colored with {@code color}
     * @throws NullPointerException if {@code color} is {@code null}
     */
    @NonNull
    public StyledText color(@NonNull final TerminalColor color) {
        return new StyledText(this.text, Asserts.assertNotNull(color, "@StyledText.color: color cannot be null"), this.styles);
    }

    /**
     * @param styles the attributes to add to this text's current styles
     * @return a new {@link StyledText} identical to this one, with {@code styles} added
     * @throws NullPointerException if {@code styles} is {@code null}
     */
    @NonNull
    public StyledText style(@NonNull final TerminalStyle... styles) {
        Asserts.assertNotNull(styles, "@StyledText.style: styles cannot be null");
        final Set<TerminalStyle> merged = EnumSet.copyOf(this.styles);
        merged.addAll(Set.of(styles));
        return new StyledText(this.text, this.color, merged);
    }

    /**
     * @return the plain, unstyled text this instance carries
     */
    @NonNull
    public String plain() {
        return this.text;
    }

    /**
     * @return {@code text} wrapped in this instance's ANSI color/style escape sequences, followed by a reset sequence - ready to print directly to an ANSI-capable sink
     */
    @NonNull
    public String render() {
        final StringBuilder builder = new StringBuilder();
        if (this.color != null) builder.append(this.color.ansiCode());
        this.styles.forEach(style -> builder.append(style.ansiCode()));
        builder.append(this.text);
        if (this.color != null || !this.styles.isEmpty()) builder.append(RESET);
        return builder.toString();
    }

}
