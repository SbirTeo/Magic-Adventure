package com.teolo.magixguard.sanctions;

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
public final class SiteSync {

    private final JavaPlugin plugin;
    private final SanctionsDao dao;
    private final SanctionsService service;

    public SiteSync(JavaPlugin plugin, SanctionsDao dao, SanctionsService service) {
        this.plugin = plugin;
        this.dao = dao;
        this.service = service;
    }

    /** Un giro di controllo. Va chiamato fuori dal thread principale. */
    public void pass() {
        eseguiRevoche();
        eseguiConferme();
    }

    private void eseguiRevoche() {
        try {
            List<Sanction> revoche = dao.revocheDaApplicare();
            for (Sanction s : revoche) {
                Bukkit.getScheduler().runTask(plugin, () -> service.applyRevokeFromSite(s));
                dao.revokeApplied(s.id());
                plugin.getLogger().info("Revoca eseguita dal gestionale: n." + s.id()
                        + " (" + s.name() + ", " + s.type().code() + ").");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Revoche non lette dal sito: " + e.getMessage());
        }
    }

    private void eseguiConferme() {
        try {
            List<SanctionsDao.Proposta> confermate = dao.proposteConfermate();
            for (SanctionsDao.Proposta p : confermate) {
                long now = System.currentTimeMillis();
                long fine = p.durationSeconds() == null || !p.type().hasDuration()
                        ? Duration.PERMANENTE
                        : now + p.durationSeconds() * 1000L;

                Sanction s = new Sanction(0, p.uuid(), p.name(), p.type(), p.category(), p.reason(),
                        p.scope(), p.points(), now, fine, p.decisaDa(), false, null);

                // Qui non si ricontrolla niente: la conferma di una persona E' la decisione.
                int id = service.apply(s, Policy.Outcome.si(), "coda", null,
                        p.durationSeconds() == null ? Duration.PERMANENTE : p.durationSeconds() * 1000L);
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
