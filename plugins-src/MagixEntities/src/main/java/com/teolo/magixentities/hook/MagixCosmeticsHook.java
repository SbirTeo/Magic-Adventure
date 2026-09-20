package com.teolo.magixentities.hook;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/**
 * Collegamento col plugin MagixCosmetics, che disegna l'aureola colorata sopra i VIP. Un'entita'
 * di MagixEntities in modalita' skin "mirror" (ogni giocatore la vede con la PROPRIA skin, vedi
 * {@code NpcDef#isSkinMirror()}) deve riprodurre anche l'aureola del giocatore che la sta
 * guardando, ma solo se in quel momento quel giocatore ce l'ha davvero attiva (permesso, colore
 * scelto, non spenta con /halo off, non in combattimento...).
 *
 * <p>Nessuna dipendenza Maven verso MagixCosmetics: ogni plugin di questo repository si compila
 * per conto suo (vedi {@code .github/workflows/deploy-plugin.yml}), quindi si parla per
 * RIFLESSIONE, chiamando i metodi pubblici di MagixCosmetics per nome — stesso principio gia'
 * usato per l'hook di MagixPack in MagixFactions ({@code MagixPackHook}).
 *
 * <p>Se MagixCosmetics non e' installato o non e' abilitato, MagixEntities degrada morbidamente:
 * i mirror restano senza aureola, il resto del plugin funziona lo stesso.
 */
public final class MagixCosmeticsHook {

    private static Object haloManager;
    private static Method mEffectiveColor, mDrawAt;

    private MagixCosmeticsHook() {}

    public static void setup(JavaPlugin owner) {
        haloManager = null;
        Plugin mc = Bukkit.getPluginManager().getPlugin("MagixCosmetics");
        if (mc == null || !mc.isEnabled()) return;
        try {
            Method mHalo = mc.getClass().getMethod("halo");
            Object halo = mHalo.invoke(mc);
            mEffectiveColor = halo.getClass().getMethod("effectiveColor", Player.class);
            mDrawAt = halo.getClass().getMethod("drawAt", Location.class, Color.class);
            haloManager = halo;
        } catch (ReflectiveOperationException e) {
            owner.getLogger().warning("MagixCosmetics trovato ma con un'API diversa da quella attesa ("
                    + e.getMessage() + "): niente aureola sui mirror. Aggiorna entrambi i plugin insieme.");
        }
    }

    public static boolean enabled() {
        return haloManager != null;
    }

    /** Disegna in {@code base} l'aureola di {@code viewer}, solo se in questo momento ce l'ha davvero attiva. */
    public static void drawHaloIfActive(Player viewer, Location base) {
        if (haloManager == null) return;
        try {
            Object color = mEffectiveColor.invoke(haloManager, viewer);
            if (color != null) mDrawAt.invoke(haloManager, base, color);
        } catch (ReflectiveOperationException ignored) {
            // MagixCosmetics ha cambiato API a caldo (jar diverso senza riavvio): si ignora finche'
            // non arriva un vero riavvio, niente aureola sui mirror nel frattempo.
        }
    }
}
