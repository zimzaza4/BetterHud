package kr.toxicity.hud.api.component;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Component with width
 * @param component component
 * @param width width
 */
public record WidthComponent(@NotNull TextComponent.Builder component, int width) {

    private static final int CENTER_SPACE_CODEPOINT = 0xD0000;

    /**
     * Composes components the way BetterHud composes its bossbar, and applies the given font to the result.
     * @param components components
     * @param font font key
     * @return composed component
     */
    public static @NotNull WidthComponent compose(@NotNull List<WidthComponent> components, @NotNull Key font) {
        var builder = Component.text().content(charOf(CENTER_SPACE_CODEPOINT - 1));
        for (var component : components) {
            builder.append(component.component());
            builder.append(Component.text().content(charOf(CENTER_SPACE_CODEPOINT - component.width)));
        }
        return new WidthComponent(builder.font(font), 0);
    }

    private static @NotNull String charOf(int codepoint) {
        if (codepoint <= 0xFFFF) return String.valueOf((char) codepoint);
        var t = codepoint - 0x10000;
        return new String(new char[]{
                (char) ((t >> 10) + 0xD800),
                (char) ((t & 1023) + 0xDC00)
        });
    }

    /**
     * Adds component
     * @param other other
     * @return Merged component
     */
    public @NotNull WidthComponent plus(@NotNull WidthComponent other) {
        return other.component.content().isEmpty() && other.component.children().isEmpty() ? this : new WidthComponent(component.append(other.component), width + other.width);
    }

    /**
     * Copy component
     * @return copied component
     */
    public @NotNull WidthComponent copy() {
        return new WidthComponent(component.build().toBuilder(), width);
    }
}
