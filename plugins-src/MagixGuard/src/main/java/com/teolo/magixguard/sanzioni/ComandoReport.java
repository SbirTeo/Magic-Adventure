package com.teolo.magixguard.sanzioni;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /report <giocatore> <motivo>}: la segnalazione di un giocatore.
 *
 * <p>E' il canale che fa emergere quello che nessun algoritmo vede — truffe, molestie, accordi
 * fra due account, comportamenti che stanno nelle intenzioni e non nei pacchetti. Finisce nella
 * <b>stessa coda</b> dei rilevamenti automatici, cosi' lo staff ha un posto solo da guardare.</p>
 *
 * <p>Una segnalazione non e' una sanzione e non ne fa scattare nessuna da sola: apre un caso. A
 * decidere e' sempre una persona, dal gestionale.</p>
 *
 * <p>Contro l'abuso ci sono tre freni, tutti configurabili: una pausa fra una segnalazione e
 * l'altra, un numero massimo di casi aperti a testa, e un motivo che deve essere scritto davvero
 * («barare» non dice niente a chi dovra' controllare).</p>
 */
public final class ComandoReport implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final SanzioniConfig cfg;
    private final ServizioSanzioni servizio;
    private final SanzioniDao dao;

    /** Ultima segnalazione di ognuno, per far rispettare la pausa. */
    private final Map<UUID, Long> ultima = new ConcurrentHashMap<>();

    public ComandoReport(JavaPlugin plugin, SanzioniConfig cfg, ServizioSanzioni servizio, SanzioniDao dao) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.servizio = servizio;
        this.dao = dao;
    }

    @Override
    public boolean onCommand(CommandSender chi, Command comando, String etichetta, String[] args) {
        if (!cfg.reportAttivo) {
            chi.sendMessage(Testo.msg("&7Le segnalazioni sono disattivate su questo server."));
            return true;
        }
        if (!(chi instanceof Player mittente)) {
            chi.sendMessage(Testo.msg("&7Le segnalazioni le mandano i giocatori. &7I casi aperti si leggono nel gestionale del sito, in Sanzioni."));
            return true;
        }
        if (args.length < 2) {
            chi.sendMessage(Testo.msg("&#FFD166Uso: &f/report <giocatore> <motivo>"));
            chi.sendMessage(Testo.panel("&7Scrivi cosa e' successo, non solo l'accusa: chi controllera' "
                    + "non era li'."));
            return true;
        }

        String bersaglio = args[0];
        String motivo = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();

        if (bersaglio.equalsIgnoreCase(mittente.getName())) {
            chi.sendMessage(Testo.msg("&#FF6B6BNon puoi segnalare te stesso."));
            return true;
        }
        if (motivo.length() < cfg.reportMotivoMinimo) {
            chi.sendMessage(Testo.msg("&#FF6B6BSpiega meglio: &7servono almeno "
                    + cfg.reportMotivoMinimo + " caratteri. Cosa ha fatto, dove, quando."));
            return true;
        }

        long adesso = System.currentTimeMillis();
        long precedente = ultima.getOrDefault(mittente.getUniqueId(), 0L);
        long pausa = cfg.reportPausaSecondi * 1000L;
        if (adesso - precedente < pausa) {
            long restano = (pausa - (adesso - precedente)) / 1000;
            chi.sendMessage(Testo.msg("&7Aspetta ancora &f" + restano + " secondi&7 prima di segnalare di nuovo."));
            return true;
        }

        // Da qui in poi si lavora sul database: fuori dal thread principale.
        Player bersaglioOnline = Bukkit.getPlayerExact(bersaglio);
        String dettaglio = componiContesto(mittente, bersaglio, bersaglioOnline);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            UUID uuid = risolvi(bersaglio, bersaglioOnline);
            if (uuid == null) {
                chi.sendMessage(Testo.msg("&#FF6B6BNon conosco nessuno con quel nome. &7Controlla come si scrive."));
                return;
            }
            try {
                int aperti = dao.reportApertiDi(mittente.getUniqueId().toString());
                if (aperti >= cfg.reportMassimoAperti) {
                    chi.sendMessage(Testo.msg("&7Hai gia' &f" + aperti + "&7 segnalazioni in attesa di risposta. "
                            + "Aspetta che lo staff le guardi."));
                    return;
                }

                // Il tipo qui e' solo un segnaposto: la segnalazione non propone una pena,
                // apre un caso. Chi lo chiude sceglie il provvedimento nel gestionale.
                Sanzione caso = new Sanzione(0, uuid, bersaglio, Tipo.WARN, "report.confermato",
                        motivo, Ambito.ENTRAMBI, 0, adesso, Durata.PERMANENTE,
                        mittente.getName(), false, null);

                dao.proponi(caso, 0L, "report", dettaglio);
                ultima.put(mittente.getUniqueId(), adesso);

                chi.sendMessage(Testo.msg("&#A8DC2CSegnalazione inviata. &7Lo staff la trova nel gestionale "
                        + "con la tua posizione e l'ora. Grazie."));

                servizio.avvisaStaff("&#FFD166Segnalazione&f " + mittente.getName() + " &7ha segnalato &f"
                        + bersaglio + " &7— " + motivo);
            } catch (SQLException e) {
                chi.sendMessage(Testo.msg("&#FF6B6BSegnalazione non inviata: riprova fra poco."));
                plugin.getLogger().warning("Segnalazione non registrata: " + e.getMessage());
            }
        });
        return true;
    }

    /**
     * Il contesto che lo staff trovera' allegato: chi ha segnalato, dov'era, dov'era il
     * segnalato, e se in quel momento era collegato. Sono le prime tre cose che serve sapere
     * per capire se andare a guardare subito o con calma.
     */
    private String componiContesto(Player mittente, String bersaglio, Player bersaglioOnline) {
        StringBuilder b = new StringBuilder();
        b.append("Segnalata da: ").append(mittente.getName()).append('\n');
        b.append("Quando: ").append(com.teolo.magixguard.util.Fmt.dateTime(System.currentTimeMillis())).append('\n');
        b.append("Chi segnala si trovava: ").append(posizione(mittente.getLocation())).append('\n');
        if (bersaglioOnline != null) {
            b.append("Il segnalato era ONLINE, a ").append(posizione(bersaglioOnline.getLocation())).append('\n');
            double distanza = bersaglioOnline.getWorld().equals(mittente.getWorld())
                    ? bersaglioOnline.getLocation().distance(mittente.getLocation()) : -1;
            b.append("Distanza fra i due: ")
             .append(distanza < 0 ? "in mondi diversi" : Math.round(distanza) + " blocchi").append('\n');
        } else {
            b.append("Il segnalato NON era collegato in quel momento.").append('\n');
        }
        b.append("Segnalato: ").append(bersaglio);
        return b.toString();
    }

    private static String posizione(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    private UUID risolvi(String nome, Player online) {
        if (online != null) {
            return online.getUniqueId();
        }
        try {
            UUID daSito = dao.uuidDalNome(nome);
            if (daSito != null) {
                return daSito;
            }
        } catch (SQLException ignored) {
            // il sito non risponde: si prova con quello che sa Bukkit
        }
        org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayer(nome);
        return off.hasPlayedBefore() ? off.getUniqueId() : null;
    }

    @Override
    public List<String> onTabComplete(CommandSender chi, Command comando, String etichetta, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(chi) && p.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}
