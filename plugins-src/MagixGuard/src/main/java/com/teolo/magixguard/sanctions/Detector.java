package com.teolo.magixguard.sanctions;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.UUID;

/**
 * La porta d'ingresso delle violazioni: <b>tutto</b> quello che il server nota — un messaggio
 * bloccato, un verdetto dell'anticheat, uno scavo che non torna, un anti-AFK aggirato — entra
 * da qui e da nessun'altra parte.
 *
 * <p>Cosa succede a ogni violazione, in ordine:</p>
 * <ol>
 *   <li>si registra il fatto con i punti della sua categoria;</li>
 *   <li>si ricalcola il totale del giocatore, <b>con il decadimento gia' applicato</b>;</li>
 *   <li>si guarda se ha appena superato una soglia — solo se l'ha <i>appena</i> superata:
 *       chi resta sopra i 50 punti non si becca un ban a ogni sciocchezza successiva;</li>
 *   <li>se si', il provvedimento passa dalla {@link Policy}, che decide se applicarlo o
 *       metterlo in coda per una persona.</li>
 * </ol>
 *
 * <p>Le classi che rilevano (chat, xray, afk, anticheat) non sanno niente di punti, soglie e
 * sanzioni: dicono solo <i>cosa hanno visto</i>. E' quello che permette di aggiungerne una
 * quinta domani senza toccare il resto.</p>
 */
public final class Detector {

    private final JavaPlugin plugin;
    private final SanctionsConfig cfg;
    private final SanctionsService servizio;
    private final ViolationsDao violazioni;
    private final PointsLog registro;

    public Detector(JavaPlugin plugin, SanctionsConfig cfg, SanctionsService servizio,
                      ViolationsDao violazioni, PointsLog registro) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.servizio = servizio;
        this.violazioni = violazioni;
        this.registro = registro;
    }

    /**
     * Registra una violazione. Si puo' chiamare da qualunque thread: il giro sul database viene
     * spostato via da solo.
     *
     * @param categoria il codice in sanzioni.yml (chat.spam, cheat.xray, afk.elusione...)
     * @param fonte     chi l'ha vista (chat, xray, afk, grim, staff)
     * @param dettaglio le prove, gia' scritte perche' le legga una persona
     */
    public void rileva(UUID uuid, String nome, String categoria, String fonte, String dettaglio) {
        rileva(uuid, nome, categoria, fonte, dettaglio, 1.0);
    }

    /**
     * Come sopra, ma con un moltiplicatore sui punti: serve a chi misura una <i>gravita'</i>
     * (l'anticheat manda quante volte ha sbagliato, l'anti-xray quanto e' fuori scala).
     * Resta comunque il tetto della categoria: un moltiplicatore non puo' trasformare uno
     * spam in un ban.
     */
    public void rileva(UUID uuid, String nome, String categoria, String fonte, String dettaglio,
                       double moltiplicatore) {
        SanctionsConfig.Categoria cat = cfg.categoria(categoria);
        int punti = (int) Math.round(cat.punti() * Math.max(0.1, Math.min(3.0, moltiplicatore)));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                double prima = registro.punti(uuid);
                int idViolazione = violazioni.inserisci(
                        Violation.nuova(uuid, nome, categoria, punti, fonte, dettaglio));
                double dopo = prima + punti;

                SanctionsConfig.Soglia soglia = sogliaAppenaSuperata(prima, dopo);
                if (soglia == null) {
                    avvisaSeServe(cat, nome, categoria, dettaglio, dopo);
                    return;
                }

                long adesso = System.currentTimeMillis();
                long fine = soglia.durata() == Duration.PERMANENTE || !soglia.tipo().haDurata()
                        ? Duration.PERMANENTE : adesso + soglia.durata();

                String motivo = cat.nome() + " (" + Math.round(dopo) + " punti)";
                Sanction s = new Sanction(0, uuid, nome, soglia.tipo(), categoria, motivo,
                        cat.ambito(), 0, adesso, fine, null, true, null);

                Policy.Esito esito = servizio.politica()
                        .controllaAutomatismo(cat, soglia.tipo(), soglia.durata());

                int idSanzione = servizio.applica(s, esito, fonte,
                        componiDettaglio(dettaglio, prima, dopo, soglia), soglia.durata());
                if (idSanzione > 0) {
                    violazioni.collega(idViolazione, idSanzione);
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Violazione non registrata (" + nome + ", "
                        + categoria + "): " + e.getMessage());
            }
        });
    }

    /**
     * La soglia che il giocatore ha superato <b>adesso</b>, cioe' quella che prima non aveva
     * raggiunto e ora si'. Null se non ne ha superata nessuna con questa violazione.
     */
    private SanctionsConfig.Soglia sogliaAppenaSuperata(double prima, double dopo) {
        SanctionsConfig.Soglia trovata = null;
        for (SanctionsConfig.Soglia s : cfg.soglie) {   // ordinate dalla piu' alta
            if (dopo >= s.punti() && prima < s.punti()) {
                trovata = s;
                break;   // la piu' alta fra quelle appena superate
            }
        }
        return trovata;
    }

    /**
     * Certe categorie non sanzionano mai da sole ma vanno viste subito da una persona:
     * i dati personali sono il caso per cui questa riga esiste.
     */
    private void avvisaSeServe(SanctionsConfig.Categoria cat, String nome, String categoria,
                               String dettaglio, double punti) {
        if (cat.automatico()) {
            return;
        }
        servizio.avvisaStaff("&#FFD166" + cat.nome() + "&f " + nome + " &7— "
                + (dettaglio == null ? "" : primaRiga(dettaglio))
                + " &8(" + Math.round(punti) + " punti)");
    }

    /** Il dettaglio che finisce nella coda, con il conto dei punti gia' fatto. */
    private String componiDettaglio(String dettaglio, double prima, double dopo,
                                    SanctionsConfig.Soglia soglia) {
        StringBuilder b = new StringBuilder();
        b.append("Punti prima: ").append(Math.round(prima))
         .append(" -> dopo: ").append(Math.round(dopo))
         .append(" (soglia superata: ").append(soglia.punti()).append(")\n");
        b.append("Provvedimento previsto: ").append(soglia.tipo().etichetta())
         .append(", ").append(Duration.scrivi(soglia.durata())).append("\n\n");
        if (dettaglio != null) {
            b.append(dettaglio);
        }
        return b.toString();
    }

    private static String primaRiga(String s) {
        int a_capo = s.indexOf('\n');
        String riga = a_capo < 0 ? s : s.substring(0, a_capo);
        return riga.length() > 90 ? riga.substring(0, 90) + "..." : riga;
    }

    /** Comodita' per i rilevatori: il nome da mostrare per un giocatore. */
    public static String nomeDi(Player p) {
        return p.getName();
    }
}
