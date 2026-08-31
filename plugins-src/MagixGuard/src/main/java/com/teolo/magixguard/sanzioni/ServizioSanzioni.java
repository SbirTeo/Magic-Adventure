package com.teolo.magixguard.sanzioni;

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
 * <p>Cosa fa, in ordine: decide se applicare o proporre (vedi {@link Politica}), scrive nel
 * database del sito, fa valere il provvedimento in partita, avvisa il giocatore e lo staff.</p>
 *
 * <p>Tutto quello che tocca il database va fuori dal thread principale; tutto quello che tocca
 * un giocatore torna sul thread principale. Le due cose non si mescolano mai.</p>
 */
public final class ServizioSanzioni {

    private final JavaPlugin plugin;
    private final SanzioniConfig cfg;
    private final SanzioniDao dao;
    private final RegistroPunti registro;
    private final Politica politica;
    private final ViolazioniDao violazioni;

    /**
     * I silenziati in memoria: la chat non puo' aspettare una query a ogni messaggio.
     * Si riempie al join e quando si applica o revoca un mute.
     */
    private final Map<UUID, Sanzione> muti = new ConcurrentHashMap<>();

    public ServizioSanzioni(JavaPlugin plugin, SanzioniConfig cfg, SanzioniDao dao,
                            RegistroPunti registro, Politica politica, ViolazioniDao violazioni) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.dao = dao;
        this.registro = registro;
        this.politica = politica;
        this.violazioni = violazioni;
    }

    public SanzioniDao dao() {
        return dao;
    }

    public RegistroPunti registro() {
        return registro;
    }

    public Politica politica() {
        return politica;
    }

    // ------------------------------------------------------------------ applicare

    /**
     * Applica (o propone) un provvedimento. Da chiamare gia' fuori dal thread principale.
     *
     * @param esito  il risultato del controllo di {@link Politica}: se dice di proporre, qui
     *               non si discute — finisce in coda
     * @param fonte  chi l'ha chiesto: staff, chat, grim, xray, afk, report
     * @param dettaglio riassunto delle prove per la coda, o null
     * @return il numero del provvedimento se e' stato applicato, 0 se e' finito in coda
     */
    public int applica(Sanzione s, Politica.Esito esito, String fonte, String dettaglio, long durata) {
        try {
            if (!esito.applica()) {
                dao.proponi(s, durata, fonte, dettaglio);
                avvisaStaff("&#FFD166Proposta in attesa: &f" + s.nome() + " &7— "
                        + s.tipo().etichetta().toLowerCase() + ", " + esito.motivoProposta()
                        + ". &7Aperta nel gestionale.");
                return 0;
            }

            int id = dao.inserisci(s);
            Sanzione applicata = s.conId(id);
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
    private void faiValere(Sanzione s) {
        if (!s.ambito().tocca(true)) {
            return;   // e' una sanzione da sito: in partita non cambia niente
        }
        Player p = Bukkit.getPlayer(s.uuid());

        switch (s.tipo()) {
            case BAN -> {
                if (p != null) {
                    p.kick(Testo.c(messaggioBan(s)));
                }
            }
            case KICK -> {
                if (p != null) {
                    p.kick(Testo.c(Testo.sostituisci(cfg.messaggioKick, "{motivo}", s.motivo())));
                }
            }
            case MUTE -> {
                muti.put(s.uuid(), s);
                if (p != null) {
                    p.sendMessage(Testo.msg("&#FF6B6BSei stato silenziato: &f" + s.motivo()
                            + " &7(" + s.durataLeggibile() + ")"));
                }
            }
            case WARN -> {
                if (p != null) {
                    p.sendMessage(Testo.msg("&#FFD166Richiamo: &f" + s.motivo()));
                }
            }
        }
    }

    /** Il messaggio di espulsione di un ban, coi segnaposto gia' sostituiti. */
    public String messaggioBan(Sanzione s) {
        return Testo.sostituisci(cfg.messaggioBan,
                "{motivo}", s.motivo(),
                "{durata}", s.durataLeggibile(),
                "{scadenza}", s.fine() == Durata.PERMANENTE ? "mai" : Durata.mancante(s.fine()),
                "{id}", String.valueOf(s.id()));
    }

    // ------------------------------------------------------------------ revocare

    /** Toglie l'ultima sanzione attiva di quel tipo. Ritorna l'id revocato, 0 se non c'era. */
    public int revoca(UUID uuid, Tipo tipo, String staff, String motivo) throws SQLException {
        int id = dao.revocaUltima(uuid, tipo, staff, motivo);
        if (id > 0 && tipo == Tipo.MUTE) {
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
    public void applicaRevocaDalSito(Sanzione s) {
        if (s.tipo() == Tipo.MUTE) {
            muti.remove(s.uuid());
            Player p = Bukkit.getPlayer(s.uuid());
            if (p != null) {
                p.sendMessage(Testo.msg("&#A8DC2CPuoi di nuovo scrivere in chat."));
            }
        }
        // Per il ban non c'e' niente da fare in partita: il controllo avviene all'ingresso,
        // e da questo momento la riga non lo blocca piu'.
    }

    // ------------------------------------------------------------------ mute

    /** Il mute attivo di un giocatore, o null. Legge solo la memoria: la chat non aspetta. */
    public Sanzione muto(UUID uuid) {
        Sanzione s = muti.get(uuid);
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
            for (Sanzione s : dao.attiveInGioco(uuid)) {
                if (s.tipo() == Tipo.MUTE) {
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
            Bukkit.getConsoleSender().sendMessage(Testo.msg(testo));
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("magixguard.alerts")) {
                    p.sendMessage(Testo.msg(testo));
                }
            }
        });
    }
}
