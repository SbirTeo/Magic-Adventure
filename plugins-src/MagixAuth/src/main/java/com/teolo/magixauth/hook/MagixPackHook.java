package com.teolo.magixauth.hook;

import com.teolo.magixauth.resourcepack.PackContent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Collegamento col plugin MagixPack, che serve il resource pack UNICO del server (un client ne
 * applica solo uno alla volta). MagixAuth vi registra le schermate di accesso e i tasti del
 * tastierino OTP (vedi {@link PackContent}); non consuma l'invio (non ha bisogno di sapere se il
 * pacchetto e' disponibile o di rimandarlo: al join ci pensa MagixPack da solo).
 *
 * <p>Nessuna dipendenza Maven verso MagixPack: ogni plugin di questo repository si compila per
 * conto suo (vedi {@code .github/workflows/deploy-plugin.yml}), quindi si parla per RIFLESSIONE,
 * chiamando i metodi pubblici del plugin MagixPack per nome — stesso principio gia' usato per la
 * chat live del sito verso MagixFactions ({@code MagixFactions.broadcastWebChat}).
 *
 * <p>Se MagixPack non e' installato o non e' abilitato, MagixAuth degrada morbidamente: le
 * schermate di accesso restano fatte di caratteri PUA che il client non conosce (quadratini), ma
 * il login funziona lo stesso — e' solo grafica.
 */
public final class MagixPackHook {

    private static Plugin magixPack;
    private static Method mRegister, mUnregister;

    private MagixPackHook() {}

    public static void setup(JavaPlugin owner) {
        magixPack = null;
        Plugin mp = Bukkit.getPluginManager().getPlugin("MagixPack");
        if (mp == null || !mp.isEnabled()) return;
        try {
            mRegister = mp.getClass().getMethod("registerPack", Plugin.class, Map.class);
            mUnregister = mp.getClass().getMethod("unregisterPack", Plugin.class);
            magixPack = mp;
        } catch (ReflectiveOperationException e) {
            owner.getLogger().warning("MagixPack trovato ma con un'API diversa da quella attesa ("
                    + e.getMessage() + "): schermate di accesso senza il pacchetto grafico. "
                    + "Aggiorna entrambi i plugin insieme.");
        }
    }

    public static boolean enabled() {
        return magixPack != null;
    }

    /** Legge e registra il contenuto di MagixAuth in MagixPack. */
    public static void registerOwnPack(JavaPlugin owner) {
        if (!enabled()) return;
        try {
            mRegister.invoke(magixPack, owner, PackContent.build(owner));
        } catch (Exception e) {
            owner.getLogger().warning("Registrazione del pacchetto risorse fallita: " + e.getMessage());
        }
    }

    /** Da chiamare all'onDisable: toglie il contenuto di MagixAuth dal pacchetto condiviso. */
    public static void unregisterOwnPack(JavaPlugin owner) {
        if (!enabled()) return;
        try {
            mUnregister.invoke(magixPack, owner);
        } catch (Exception ignored) { /* in spegnimento non c'e' nulla di utile da fare */ }
    }
}
