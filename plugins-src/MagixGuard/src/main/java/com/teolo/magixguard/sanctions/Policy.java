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
    public record Outcome(boolean apply, String proposedReason) {

        /** Si applica subito. (Non si puo' chiamare "applica": e' il nome dell'accessore.) */
        public static Outcome si() {
            return new Outcome(true, null);
        }

        public static Outcome proponi(String perche) {
            return new Outcome(false, perche);
        }
    }

    // ------------------------------------------------------------------ persone

    /**
     * Il grado di chi ha dato il comando, per come lo conosce LuckPerms. La console e' "admin":
     * chi ha la console ha gia' tutto il server in mano, i tetti non aggiungerebbero niente.
     */
    public String rank(CommandSender chi) {
        if (!(chi instanceof Player p)) {
            return "admin";
        }
        String gruppo = gruppoPrimario(p);
        return gruppo == null ? "" : gruppo;
    }

    /** Puo' questa persona dare questa sanzione con questa durata? */
    public Outcome checkStaff(CommandSender chi, Type type, long duration) {
        if (!(chi instanceof Player)) {
            return Outcome.si();
        }
        String rank = rank(chi);
        SanctionsConfig.Tetto tetto = cfg.tetto(rank);
        if (tetto.consente(type, duration)) {
            return Outcome.si();
        }
        String limite = type == Type.BAN ? Duration.write(tetto.ban()) : Duration.write(tetto.mute());
        return Outcome.proponi("il grado " + (rank.isEmpty() ? "senza poteri" : rank)
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
    public Outcome checkAutomation(SanctionsConfig.Category category, Type type, long duration) {
        if (type == Type.BAN && duration == Duration.PERMANENTE) {
            return Outcome.proponi("un ban permanente lo decide sempre una persona");
        }
        if (cfg.mode.equals("proposta")) {
            return Outcome.proponi("il plugin e' impostato per non applicare nulla da solo");
        }
        if (!category.automatic()) {
            return Outcome.proponi("la categoria " + category.name() + " non e' mai automatica");
        }
        if (cfg.mode.equals("automatico")) {
            return withinCap(duration);
        }
        // modo "misto": come automatico, ma sempre entro il tetto di durata
        return withinCap(duration);
    }

    private Outcome withinCap(long duration) {
        if (cfg.maxAutomaticDuration <= 0) {
            return duration == 0 ? Outcome.si()
                    : Outcome.proponi("gli automatismi di durata sono spenti");
        }
        if (duration == Duration.PERMANENTE || duration > cfg.maxAutomaticDuration) {
            return Outcome.proponi("supera il tetto automatico di "
                    + Duration.write(cfg.maxAutomaticDuration));
        }
        return Outcome.si();
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
