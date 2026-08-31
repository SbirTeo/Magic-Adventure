package com.teolo.magixguard.sanctions;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Chi puo' decidere cosa, e cosa puo' decidere il plugin da solo.
 *
 * <p>Due domande separate, e conviene tenerle separate:</p>
 * <ul>
 *   <li><b>Una persona</b> puo' dare questa sanzione? Dipende dal tetto del suo grado.</li>
 *   <li><b>Il plugin</b> puo' darla da solo? Dipende dal modo, dalla categoria e dalla durata.</li>
 * </ul>
 *
 * <p>In tutti e due i casi la risposta negativa non butta via niente: la sanzione diventa una
 * <b>proposta</b> in coda, con le prove gia' allegate. Chi ha visto il problema non perde il
 * lavoro fatto, e chi ha il potere di decidere trova tutto pronto.</p>
 */
public final class Policy {

    private final SanctionsConfig cfg;

    public Policy(SanctionsConfig cfg) {
        this.cfg = cfg;
    }

    /** L'esito di un controllo: si applica, oppure si propone (e si dice perche'). */
    public record Esito(boolean applica, String motivoProposta) {

        /** Si applica subito. (Non si puo' chiamare "applica": e' il nome dell'accessore.) */
        public static Esito si() {
            return new Esito(true, null);
        }

        public static Esito proponi(String perche) {
            return new Esito(false, perche);
        }
    }

    // ------------------------------------------------------------------ persone

    /**
     * Il grado di chi ha dato il comando, per come lo conosce LuckPerms. La console e' "admin":
     * chi ha la console ha gia' tutto il server in mano, i tetti non aggiungerebbero niente.
     */
    public String grado(CommandSender chi) {
        if (!(chi instanceof Player p)) {
            return "admin";
        }
        String gruppo = gruppoPrimario(p);
        return gruppo == null ? "" : gruppo;
    }

    /** Puo' questa persona dare questa sanzione con questa durata? */
    public Esito controllaStaff(CommandSender chi, Type tipo, long durata) {
        if (!(chi instanceof Player)) {
            return Esito.si();
        }
        String grado = grado(chi);
        SanctionsConfig.Tetto tetto = cfg.tetto(grado);
        if (tetto.consente(tipo, durata)) {
            return Esito.si();
        }
        String limite = tipo == Type.BAN ? Duration.scrivi(tetto.ban()) : Duration.scrivi(tetto.mute());
        return Esito.proponi("il grado " + (grado.isEmpty() ? "senza poteri" : grado)
                + " arriva fino a " + limite + ": la sanzione va confermata da un grado superiore");
    }

    // ------------------------------------------------------------------ automatismi

    /**
     * Il plugin puo' applicare da solo questa sanzione?
     *
     * <p>Il <b>ban permanente non e' mai automatico</b>, qualunque cosa dica la configurazione:
     * e' la decisione piu' grave che esista e non la prende una macchina. Non e' un valore
     * regolabile di proposito.</p>
     */
    public Esito controllaAutomatismo(SanctionsConfig.Categoria categoria, Type tipo, long durata) {
        if (tipo == Type.BAN && durata == Duration.PERMANENTE) {
            return Esito.proponi("un ban permanente lo decide sempre una persona");
        }
        if (cfg.modo.equals("proposta")) {
            return Esito.proponi("il plugin e' impostato per non applicare nulla da solo");
        }
        if (!categoria.automatico()) {
            return Esito.proponi("la categoria " + categoria.nome() + " non e' mai automatica");
        }
        if (cfg.modo.equals("automatico")) {
            return dentroIlTetto(durata);
        }
        // modo "misto": come automatico, ma sempre entro il tetto di durata
        return dentroIlTetto(durata);
    }

    private Esito dentroIlTetto(long durata) {
        if (cfg.durataMassimaAutomatica <= 0) {
            return durata == 0 ? Esito.si()
                    : Esito.proponi("gli automatismi di durata sono spenti");
        }
        if (durata == Duration.PERMANENTE || durata > cfg.durataMassimaAutomatica) {
            return Esito.proponi("supera il tetto automatico di "
                    + Duration.scrivi(cfg.durataMassimaAutomatica));
        }
        return Esito.si();
    }

    // ------------------------------------------------------------------ LuckPerms

    /**
     * Il gruppo primario da LuckPerms, letto per riflessione: MagixGuard non dipende da
     * LuckPerms per funzionare, e chi non ce l'ha non deve trovarsi il plugin rotto.
     */
    private String gruppoPrimario(Player p) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            return null;
        }
        try {
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object api = provider.getMethod("get").invoke(null);
            Object userManager = api.getClass().getMethod("getUserManager").invoke(api);
            Object user = userManager.getClass().getMethod("getUser", java.util.UUID.class)
                    .invoke(userManager, p.getUniqueId());
            if (user == null) {
                return null;
            }
            Object gruppo = user.getClass().getMethod("getPrimaryGroup").invoke(user);
            return gruppo == null ? null : String.valueOf(gruppo);
        } catch (Throwable t) {
            return null;
        }
    }
}
