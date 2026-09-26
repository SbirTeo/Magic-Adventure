package com.teolo.magixmenus.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * The bridge to MagixPack's item catalog (items.yml), for the {@code magixpack: <id>} key of a menu
 * item.
 *
 * MagixPack is a softdepend reached by reflection, like every plugin-to-plugin call in this
 * repository (each plugin builds on its own, see deploy-plugin.yml): without it the key simply
 * finds nothing, and the menu shows the usual red barrier explaining why.
 */
public final class MagixPackHook {

    private MagixPackHook() {
    }

    /** The item {@code id} of MagixPack's catalog built for {@code viewer} (a player-avatar item
     *  carries the viewer's head and avatar); null if MagixPack is missing or the id is unknown. */
    public static ItemStack item(String id, Player viewer) {
        Plugin mp = Bukkit.getPluginManager().getPlugin("MagixPack");
        if (mp == null || !mp.isEnabled()) {
            return null;
        }
        try {
            Method m = mp.getClass().getMethod("customItem", String.class, Player.class);
            return (ItemStack) m.invoke(mp, id, viewer);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}
