package com.teolo.magixguard.sanctions;

import com.teolo.magixguard.lang.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L'unica porta d'ingresso alle sanzioni: chiunque voglia punire qualcuno passa da qui —
 * uno staff con /ban, il filtro della chat, l'anticheat, la coda del gestionale.
 *
 * <p>Cosa fa, in ordine: decide se applicare o proporre (vedi {@link Policy}), scrive nel
 * database del sito, fa valere il provvedimento in partita, avvisa il giocatore e lo staff.</p>
 *
 * <p>Tutto quello che tocca il database va fuori dal thread principale; tutto quello che tocca
 * un giocatore torna sul thread principale. Le due cose non si mescolano mai.</p>
 */
public final class SanctionsService {

    private final JavaPlugin plugin;
    private final SanctionsConfig cfg;
    private final SanctionsDao dao;
    private final PointsLog log;
    private final Policy policy;
    private final ViolationsDao violations;
    private final Messages messages;

    /**
     * I silenziati in memoria: la chat non puo' aspettare una query a ogni messaggio.
     * Si riempie al join e quando si applica o revoca un mute.
     */
    private final Map<UUID, Sanction> muti = new ConcurrentHashMap<>();

    public SanctionsService(JavaPlugin plugin, SanctionsConfig cfg, SanctionsDao dao,
                            PointsLog log, Policy policy, ViolationsDao violations, Messages messages) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.dao = dao;
        this.log = log;
        this.policy = policy;
        this.violations = violations;
        this.messages = messages;
    }

    public SanctionsDao dao() {
        return dao;
    }

    public PointsLog log() {
        return log;
    }

    public Policy policy() {
        return policy;
    }

    // ------------------------------------------------------------------ applicare

    /**
     * Applica (o propone) un provvedimento. Da chiamare gia' fuori dal thread principale.
     *
     * @param esito  il risultato del controllo di {@link Policy}: se dice di proporre, qui
     *               non si discute — finisce in coda
     * @param fonte  chi l'ha chiesto: staff, chat, grim, xray, afk, report
     * @param dettaglio riassunto delle prove per la coda, o null
     * @return il numero del provvedimento se e' stato applicato, 0 se e' finito in coda
     */
    public int apply(Sanction s, Policy.Outcome outcome, String fonte, String dettaglio, long duration) {
        try {
            if (!outcome.apply()) {
                dao.proponi(s, duration, fonte, dettaglio);
                notifyStaff("service.proposal-pending", to -> new String[] {
                        "nome", s.name(), "tipo", messages.typeLabel(to, s.type()).toLowerCase(),
                        "motivo", outcome.proposedReason() });
                return 0;
            }

            int id = dao.inserisci(s);
            Sanction applicata = s.conId(id);
            Bukkit.getScheduler().runTask(plugin, () -> faiValere(applicata));

            notifyStaff("service.applied", to -> new String[] {
                    "tipo", messages.typeLabel(to, s.type()), "nome", s.name(), "motivo", s.reason(),
                    "durata", applicata.readableDuration(), "autore", s.autore() });
            return id;
        } catch (SQLException e) {
            plugin.getLogger().severe("Sanzione non registrata (" + s.name() + "): " + e.getMessage());
            notifyStaff("service.not-registered", to -> new String[] { "nome", s.name() });
            return 0;
        }
    }

    /** Fa valere il provvedimento su chi e' collegato adesso. Solo thread principale. */
    private void faiValere(Sanction s) {
        if (!s.scope().tocca(true)) {
            return;   // e' una sanzione da sito: in partita non cambia niente
        }
        Player p = Bukkit.getPlayer(s.uuid());

        switch (s.type()) {
            case BAN -> {
                if (p != null) {
                    p.kick(Text.c(banMessage(s)));
                }
            }
            case KICK -> {
                if (p != null) {
                    p.kick(Text.c(Text.replace(cfg.kickMessage, "{motivo}", s.reason())));
                }
            }
            case MUTE -> {
                muti.put(s.uuid(), s);
                if (p != null) {
                    p.sendMessage(Text.msg(messages.get(p, "service.muted",
                            "motivo", s.reason(), "durata", s.readableDuration())));
                }
            }
            case WARN -> {
                if (p != null) {
                    p.sendMessage(Text.msg(messages.get(p, "service.warned", "motivo", s.reason())));
                }
            }
        }
    }

    /** Il messaggio di espulsione di un ban, coi segnaposto gia' sostituiti. */
    public String banMessage(Sanction s) {
        return Text.replace(cfg.banMessage,
                "{motivo}", s.reason(),
                "{durata}", s.readableDuration(),
                "{scadenza}", s.fine() == Duration.PERMANENTE ? "mai" : Duration.mancante(s.fine()),
                "{id}", String.valueOf(s.id()));
    }

    // ------------------------------------------------------------------ revocare

    /** Toglie l'ultima sanzione attiva di quel tipo. Ritorna l'id revocato, 0 se non c'era. */
    public int revoke(UUID uuid, Type type, String staff, String reason) throws SQLException {
        int id = dao.revokeLast(uuid, type, staff, reason);
        if (id > 0 && type == Type.MUTE) {
            muti.remove(uuid);
        }
        if (id > 0 && violations != null) {
            // I punti che avevano fatto scattare il provvedimento non contano piu': se il
            // ricorso e' stato accolto, lasciare il giocatore a un passo dalla soglia
            // successiva vorrebbe dire punirlo lo stesso, a meta'.
            violations.cancelForSanction(id);
        }
        return id;
    }

    /** Esegue in partita una revoca decisa sul sito. */
    public void applyRevokeFromSite(Sanction s) {
        if (s.type() == Type.MUTE) {
            muti.remove(s.uuid());
            Player p = Bukkit.getPlayer(s.uuid());
            if (p != null) {
                p.sendMessage(Text.msg(messages.get(p, "service.unmuted")));
            }
        }
        // Per il ban non c'e' niente da fare in partita: il controllo avviene all'ingresso,
        // e da questo momento la riga non lo blocca piu'.
    }

    // ------------------------------------------------------------------ mute

    /** Il mute attivo di un giocatore, o null. Legge solo la memoria: la chat non aspetta. */
    public Sanction muto(UUID uuid) {
        Sanction s = muti.get(uuid);
        if (s == null) {
            return null;
        }
        if (!s.activate()) {
            muti.remove(uuid);
            return null;
        }
        return s;
    }

    /** Rilegge dal database i provvedimenti di chi entra, per tenere aggiornata la memoria. */
    public void loadOnJoin(UUID uuid) {
        try {
            for (Sanction s : dao.attiveInGioco(uuid)) {
                if (s.type() == Type.MUTE) {
                    muti.put(uuid, s);
                    return;
                }
            }
            muti.remove(uuid);
        } catch (SQLException e) {
            plugin.getLogger().warning("Sanzioni non lette per " + uuid + ": " + e.getMessage());
        }
    }

    public void forget(UUID uuid) {
        muti.remove(uuid);
    }

    // ------------------------------------------------------------------ staff

    /** Un messaggio a chi ha il permesso di ricevere gli avvisi, senza segnaposto. */
    public void notifyStaff(String key) {
        notifyStaff(key, to -> new String[0]);
    }

    /** Come sopra, con segnaposto uguali per tutti. */
    public void notifyStaff(String key, String... kv) {
        notifyStaff(key, to -> kv);
    }

    /**
     * Un messaggio a chi ha il permesso di ricevere gli avvisi, tradotto per ognuno per conto
     * proprio (uno staff straniero e uno italiano leggono lingue diverse dello stesso avviso).
     * I segnaposto si ricalcolano per destinatario: serve per quelli che dipendono dalla sua
     * lingua, come il nome del provvedimento.
     */
    public void notifyStaff(String key, java.util.function.Function<CommandSender, String[]> kv) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            CommandSender console = Bukkit.getConsoleSender();
            console.sendMessage(Text.msg(messages.get(console, key, kv.apply(console))));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("magixguard.alerts")) {
                    p.sendMessage(Text.msg(messages.get(p, key, kv.apply(p))));
                }
            }
        });
    }
}
