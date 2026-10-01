package com.teolo.magixscoreboard.hook;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Collegamento col plugin MagixPack, che serve il resource pack UNICO del server (un client ne
 * applica uno solo alla volta): MagixScoreboard vi registra lo shader dello sfondo della sidebar
 * (vedi {@link SidebarPack}). Per riflessione, come l'hook di MagixFactions: nessuna dipendenza
 * Maven fra i plugin, ognuno si compila da solo. Senza MagixPack lo sfondo resta quello vanilla.
 */
public final class MagixPackHook {

    private static Plugin magixPack;
    private static Method mRegister, mUnregister;

    private MagixPackHook() {}

    /** Registra (o aggiorna) i file dello sfondo della sidebar nel pacchetto condiviso. */
    public static void register(JavaPlugin owner) {
        if (!setup(owner)) return;
        try {
            Map<String, byte[]> files = SidebarPack.build(owner);
            mRegister.invoke(magixPack, owner, files);
        } catch (Exception e) {
            owner.getLogger().warning("Registrazione dello sfondo della sidebar nel pacchetto risorse fallita: "
                    + e.getMessage());
        }
    }

    /**
     * Le SCRITTE della sidebar le sposta (sidebar-position.offset-y) lo shader del testo, che nel
     * pacchetto unico e' di MagixFactions (MagixPack tiene un solo text.vsh): MagixFactions legge il
     * valore dal nostro config quando costruisce il suo pezzo. Qui gli si chiede di ricostruirlo, cosi'
     * un /mscoreboard reload sposta scritte e sfondo insieme. Per riflessione: niente dipendenze Maven.
     */
    public static void refreshFactionsPack(JavaPlugin owner) {
        Plugin mf = Bukkit.getPluginManager().getPlugin("MagixFactions");
        if (mf == null || !mf.isEnabled()) return;
        try {
            Class<?> hook = mf.getClass().getClassLoader().loadClass("com.teolo.magixfactions.hook.MagixPackHook");
            hook.getMethod("registerOwnPack", JavaPlugin.class).invoke(null, mf);
        } catch (ReflectiveOperationException | RuntimeException e) {
            owner.getLogger().warning("MagixFactions non ha potuto aggiornare lo spostamento delle scritte della "
                    + "sidebar (" + e.getMessage() + "): si sposta solo lo sfondo. Aggiorna entrambi i plugin.");
        }
    }

    /** Da chiamare all'onDisable: toglie i file di MagixScoreboard dal pacchetto condiviso. */
    public static void unregister(JavaPlugin owner) {
        if (magixPack == null) return;
        try {
            mUnregister.invoke(magixPack, owner);
        } catch (Exception ignored) { /* in spegnimento non c'e' nulla di utile da fare */ }
    }

    private static boolean setup(JavaPlugin owner) {
        if (magixPack != null && magixPack.isEnabled()) return true;
        magixPack = null;
        Plugin mp = Bukkit.getPluginManager().getPlugin("MagixPack");
        if (mp == null || !mp.isEnabled()) return false;
        try {
            mRegister = mp.getClass().getMethod("registerPack", Plugin.class, Map.class);
            mUnregister = mp.getClass().getMethod("unregisterPack", Plugin.class);
            magixPack = mp;
            return true;
        } catch (ReflectiveOperationException e) {
            owner.getLogger().warning("MagixPack trovato ma con un'API diversa da quella attesa ("
                    + e.getMessage() + "): sfondo della sidebar lasciato vanilla. Aggiorna entrambi i plugin.");
            return false;
        }
    }
}
