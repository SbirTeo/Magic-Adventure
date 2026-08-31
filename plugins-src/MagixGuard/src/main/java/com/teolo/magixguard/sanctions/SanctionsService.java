package com.teolo.magixguard.sanctions;

import org.bukkit.Bukkit;
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
    private final PointsLog registro;
    private final Policy politica;
    private final ViolationsDao violazioni;

    /**
     * I silenziati in memoria: la chat non puo' aspettare una query a ogni messaggio.
     * Si riempie al join e quando si applica o revoca un mute.
     */
    private final Map<UUID, Sanction> muti = new ConcurrentHashMap<>();

    public SanctionsService(JavaPlugin plugin, SanctionsConfig cfg, SanctionsDao dao,
                            PointsLog registro, Policy politica, ViolationsDao violazioni) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.dao = dao;
        this.registro = registro;
        this.politica = politica;
        this.violazioni = violazioni;
    }

    public SanctionsDao dao() {
        return dao;
    }

    public PointsLog registro() {
        return registro;
    }

    public Policy politica() {
        return politica;
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
    public int applica(Sanction s, Policy.Esito esito, String fonte, String dettaglio, long durata) {
        try {
            if (!esito.applica()) {
                dao.proponi(s, durata, fonte, dettaglio);
                avvisaStaff("&#FFD166Proposta in attesa: &f" + s.nome() + " &7— "
                        + s.tipo().etichetta().toLowerCase() + ", " + esito.motivoProposta()
                        + ". &7Aperta nel gestionale.");
                return 0;
            }

            int id = dao.inserisci(s);
            Sanction applicata = s.conId(id);
            Bukkit.getScheduler().runTask(plugin, () -> faiValere(applicata));

            avvisaStaff("&#A8DC2C" + s.tipo().etichetta() + "&f " + s.nome() + " &7— "
                    + s.motivo() + " &8(" + applicata.durataLeggibile() + ", da " + s.autore() + ")");
            return id;
        } catch (SQLException e) {
            plugin.getLogger().severe("Sanzione non registrata (" + s.nome() + "): " + e.getMessage());
            avvisaStaff("&#FF6B6BSanzione NON registrata per &f" + s.nome()
                    + "&#FF6B6B: il database del sito non risponde. Riprova.");
            return 0;
        }
    }

    /** Fa valere il provvedimento su chi e' collegato adesso. Solo thread principale. */
    private void faiValere(Sanction s) {
        if (!s.ambito().tocca(true)) {
            return;   // e' una sanzione da sito: in partita non cambia niente
        }
        Player p = Bukkit.getPlayer(s.uuid());

        switch (s.tipo()) {
            case BAN -> {
                if (p != null) {
                    p.kick(Text.c(messaggioBan(s)));
                }
            }
            case KICK -> {
                if (p != null) {
                    p.kick(Text.c(Text.sostituisci(cfg.messaggioKick, "{motivo}", s.motivo())));
                }
            }
            case MUTE -> {
                muti.put(s.uuid(), s);
                if (p != null) {
                    p.sendMessage(Text.msg("&#FF6B6BSei stato silenziato: &f" + s.motivo()
                            + " &7(" + s.durataLeggibile() + ")"));
                }
            }
            case WARN -> {
                if (p != null) {
                    p.sendMessage(Text.msg("&#FFD166Richiamo: &f" + s.motivo()));
                }
            }
        }
    }

    /** Il messaggio di espulsione di un ban, coi segnaposto gia' sostituiti. */
    public String messaggioBan(Sanction s) {
        return Text.sostituisci(cfg.messaggioBan,
                "{motivo}", s.motivo(),
                "{durata}", s.durataLeggibile(),
                "{scadenza}", s.fine() == Duration.PERMANENTE ? "mai" : Duration.mancante(s.fine()),
                "{id}", String.valueOf(s.id()));
    }

    // ------------------------------------------------------------------ revocare

    /** Toglie l'ultima sanzione attiva di quel tipo. Ritorna l'id revocato, 0 se non c'era. */
    public int revoca(UUID uuid, Type tipo, String staff, String motivo) throws SQLException {
        int id = dao.revocaUltima(uuid, tipo, staff, motivo);
        if (id > 0 && tipo == Type.MUTE) {
            muti.remove(uuid);
        }
        if (id > 0 && violazioni != null) {
            // I punti che avevano fatto scattare il provvedimento non contano piu': se il
            // ricorso e' stato accolto, lasciare il giocatore a un passo dalla soglia
            // successiva vorrebbe dire punirlo lo stesso, a meta'.
            violazioni.annullaPerSanzione(id);
        }
        return id;
    }

    /** Esegue in partita una revoca decisa sul sito. */
    public void applicaRevocaDalSito(Sanction s) {
        if (s.tipo() == Type.MUTE) {
            muti.remove(s.uuid());
            Player p = Bukkit.getPlayer(s.uuid());
            if (p != null) {
                p.sendMessage(Text.msg("&#A8DC2CPuoi di nuovo scrivere in chat."));
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
        if (!s.attiva()) {
            muti.remove(uuid);
            return null;
        }
        return s;
    }

    /** Rilegge dal database i provvedimenti di chi entra, per tenere aggiornata la memoria. */
    public void caricaAllIngresso(UUID uuid) {
        try {
            for (Sanction s : dao.attiveInGioco(uuid)) {
                if (s.tipo() == Type.MUTE) {
                    muti.put(uuid, s);
                    return;
                }
            }
            muti.remove(uuid);
        } catch (SQLException e) {
            plugin.getLogger().warning("Sanzioni non lette per " + uuid + ": " + e.getMessage());
        }
    }

    public void dimentica(UUID uuid) {
        muti.remove(uuid);
    }

    // ------------------------------------------------------------------ staff

    /** Un messaggio a chi ha il permesso di ricevere gli avvisi. */
    public void avvisaStaff(String testo) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Bukkit.getConsoleSender().sendMessage(Text.msg(testo));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("magixguard.alerts")) {
                    p.sendMessage(Text.msg(testo));
                }
            }
        });
    }
}
