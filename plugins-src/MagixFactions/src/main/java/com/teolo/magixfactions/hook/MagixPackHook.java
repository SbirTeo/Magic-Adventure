package com.teolo.magixfactions.hook;

import com.teolo.magixfactions.resourcepack.ResourcePackContent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Collegamento col plugin MagixPack, che serve il resource pack UNICO del server (un client ne
 * applica solo uno alla volta). MagixFactions vi registra lo shader della minimap/mappa e il logo
 * del tablist, gia' coi propri segnaposto risolti dal proprio config (vedi
 * {@link ResourcePackContent}).
 *
 * <p>Nessuna dipendenza Maven verso MagixPack: ogni plugin di questo repository si compila per
 * conto suo (vedi {@code .github/workflows/deploy-plugin.yml}), quindi si parla per RIFLESSIONE,
 * chiamando i metodi pubblici del plugin MagixPack per nome — stesso principio gia' usato per
 * l'hook di Vault ({@link Econ}), solo che li' l'interfaccia {@code Economy} e' pubblicata su un
 * repository Maven e qui non lo e'.
 *
 * <p>Se MagixPack non e' installato o non e' abilitato, MagixFactions degrada morbidamente:
 * niente mappa/minimap/logo per i giocatori, ma il resto del plugin funziona lo stesso (stessa
 * filosofia gia' usata per l'assenza di ProtocolLib/Vault/PlaceholderAPI).
 */
public final class MagixPackHook {

    private static Plugin magixPack;
    private static Method mRegister, mUnregister, mIsAvailable, mIsRequired, mSendTo;

    private MagixPackHook() {}

    public static void setup(JavaPlugin owner) {
        magixPack = null;
        Plugin mp = Bukkit.getPluginManager().getPlugin("MagixPack");
        if (mp == null || !mp.isEnabled()) return;
        try {
            mRegister = mp.getClass().getMethod("registerPack", Plugin.class, Map.class);
            mUnregister = mp.getClass().getMethod("unregisterPack", Plugin.class);
            mIsAvailable = mp.getClass().getMethod("isPackAvailable");
            mIsRequired = mp.getClass().getMethod("isPackRequired");
            mSendTo = mp.getClass().getMethod("sendPackTo", Player.class);
            magixPack = mp;
        } catch (ReflectiveOperationException e) {
            owner.getLogger().warning("MagixPack trovato ma con un'API diversa da quella attesa ("
                    + e.getMessage() + "): resource pack disabilitato. Aggiorna entrambi i plugin insieme.");
        }
    }

    public static boolean enabled() {
        return magixPack != null;
    }

    /** Legge, risolve i propri segnaposto e registra il contenuto di MagixFactions in MagixPack. */
    public static void registerOwnPack(JavaPlugin owner) {
        if (!enabled()) return;
        try {
            Map<String, byte[]> files = ResourcePackContent.build(owner);
            mRegister.invoke(magixPack, owner, files);
        } catch (Exception e) {
            owner.getLogger().warning("Registrazione del pacchetto risorse fallita: " + e.getMessage());
        }
    }

    /** Da chiamare all'onDisable: toglie il contenuto di MagixFactions dal pacchetto condiviso. */
    public static void unregisterOwnPack(JavaPlugin owner) {
        if (!enabled()) return;
        try {
            mUnregister.invoke(magixPack, owner);
        } catch (Exception ignored) { /* in spegnimento non c'e' nulla di utile da fare */ }
    }

    public static boolean isAvailable() {
        if (!enabled()) return false;
        try {
            return (boolean) mIsAvailable.invoke(magixPack);
        } catch (Exception e) {
            return false;
        }
    }

    /** true anche quando MagixPack non c'e': e' il valore piu' prudente se non si puo' saperlo
     *  davvero (chi chiama gia' controlla {@link #isAvailable()} prima di mandare qualcosa). */
    public static boolean isRequired() {
        if (!enabled()) return true;
        try {
            return (boolean) mIsRequired.invoke(magixPack);
        } catch (Exception e) {
            return true;
        }
    }

    public static void sendTo(Player p) {
        if (!enabled()) return;
        try {
            mSendTo.invoke(magixPack, p);
        } catch (Exception ignored) { }
    }
}
