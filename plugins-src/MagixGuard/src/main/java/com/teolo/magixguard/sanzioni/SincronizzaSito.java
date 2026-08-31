package com.teolo.magixguard.sanzioni;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;

/**
 * Le decisioni prese dallo staff sul sito, portate in partita.
 *
 * <p>Il sito non applica niente: registra cosa ha deciso una persona. Qui si legge quel registro
 * e si esegue. Due casi soli:</p>
 * <ul>
 *   <li>una <b>revoca</b> decisa nel gestionale — il ban c'e' ancora finche' non passiamo di qui;</li>
 *   <li>una <b>proposta confermata</b> dalla coda — diventa un provvedimento vero.</li>
 * </ul>
 *
 * <p>Stesso meccanismo della coda degli acquisti di MagixWeb, e per la stessa ragione: il sito
 * non puo' parlare al server, ma tutti e due sanno leggere e scrivere nello stesso posto.</p>
 */
public final class SincronizzaSito {

    private final JavaPlugin plugin;
    private final SanzioniDao dao;
    private final ServizioSanzioni servizio;

    public SincronizzaSito(JavaPlugin plugin, SanzioniDao dao, ServizioSanzioni servizio) {
        this.plugin = plugin;
        this.dao = dao;
        this.servizio = servizio;
    }

    /** Un giro di controllo. Va chiamato fuori dal thread principale. */
    public void giro() {
        eseguiRevoche();
        eseguiConferme();
    }

    private void eseguiRevoche() {
        try {
            List<Sanzione> revoche = dao.revocheDaApplicare();
            for (Sanzione s : revoche) {
                Bukkit.getScheduler().runTask(plugin, () -> servizio.applicaRevocaDalSito(s));
                dao.revocaApplicata(s.id());
                plugin.getLogger().info("Revoca eseguita dal gestionale: n." + s.id()
                        + " (" + s.nome() + ", " + s.tipo().codice() + ").");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Revoche non lette dal sito: " + e.getMessage());
        }
    }

    private void eseguiConferme() {
        try {
            List<SanzioniDao.Proposta> confermate = dao.proposteConfermate();
            for (SanzioniDao.Proposta p : confermate) {
                long adesso = System.currentTimeMillis();
                long fine = p.durataSecondi() == null || !p.tipo().haDurata()
                        ? Durata.PERMANENTE
                        : adesso + p.durataSecondi() * 1000L;

                Sanzione s = new Sanzione(0, p.uuid(), p.nome(), p.tipo(), p.categoria(), p.motivo(),
                        p.ambito(), p.punti(), adesso, fine, p.decisaDa(), false, null);

                // Qui non si ricontrolla niente: la conferma di una persona E' la decisione.
                int id = servizio.applica(s, Politica.Esito.si(), "coda", null,
                        p.durataSecondi() == null ? Durata.PERMANENTE : p.durataSecondi() * 1000L);
                if (id > 0) {
                    dao.codaEseguita(p.id(), id);
                    plugin.getLogger().info("Proposta confermata ed eseguita: coda n." + p.id()
                            + " -> provvedimento n." + id + ".");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Coda delle proposte non letta: " + e.getMessage());
        }
    }
}
