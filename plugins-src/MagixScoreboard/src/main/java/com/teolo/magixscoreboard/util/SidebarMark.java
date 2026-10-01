package com.teolo.magixscoreboard.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Marks the text of the sidebar so the text shader can tell it from every other GUI text.
 *
 * <p>The sidebar is moved vertically (sidebar-position.offset-y) by the text shader of the pack, which
 * sees only vertices: without a mark it moved EVERY text in the right band of the screen, item tooltips
 * included (their background stayed put, so lore and attributes fell out of the box). Here every color
 * of the sidebar becomes the nearest one whose red, green and blue are all 3 modulo 8 (at most 4 steps
 * out of 255 per channel, invisible): no vanilla color does that (0, 85, 170 and 255 are 0, 5, 2 and 7
 * modulo 8), so the shader moves only what carries the mark. MUST match the check in text.vsh of
 * MagixFactions.
 */
public final class SidebarMark {

    private SidebarMark() {}

    /** The same component with every color marked; the sidebar's default color is white. */
    public static Component apply(Component component) {
        return mark(component, NamedTextColor.WHITE);
    }

    private static Component mark(Component component, TextColor inherited) {
        TextColor own = component.color() != null ? component.color() : inherited;
        List<Component> children = component.children();
        Component out = component.color(nudge(own));
        if (children.isEmpty()) return out;
        List<Component> marked = new ArrayList<>(children.size());
        for (Component child : children) marked.add(mark(child, own));
        return out.children(marked);
    }

    private static TextColor nudge(TextColor color) {
        return TextColor.color(channel(color.red()), channel(color.green()), channel(color.blue()));
    }

    private static int channel(int value) {
        return (value & ~7) | 3;
    }
}
