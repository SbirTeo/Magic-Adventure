package com.teolo.magixguard.afk;

import com.teolo.magixguard.lang.Messages;
import com.teolo.magixguard.sanctions.Detector;
import com.teolo.magixguard.sanctions.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-AFK, in due misure che rispondono a due problemi diversi.
 *
 * <p><b>1. Niente guadagni da fermo.</b> Chi resta immobile smette di generare valore: attorno a
 * lui i mostri non nascono piu', e quello che cade per terra o l'esperienza non gli arrivano
 * addosso. Non viene espulso e non viene punito — semplicemente il gioco smette di premiare il
 * fatto di essere collegato invece che di giocare. Chi resta per chiacchierare non se ne accorge
 * nemmeno; la farm automatica si spegne da sola.</p>
 *
 * <p><b>2. Caccia ai dispositivi.</b> Chi mette un peso sul mouse, gira in barca o si fa spingere
 * da un pistone non e' un giocatore fermo: e' uno che sta <i>aggirando</i> la misura di sopra. Qui
 * si', si sanziona — ma solo su segnali che un essere umano non puo' produrre: click a distanza
 * costante al millisecondo per minuti interi, o movimento perfettamente periodico.</p>
 *
 * <p>La differenza fra le due e' tutta qui: la prima non accusa nessuno, la seconda accusa e
 * quindi deve essere certa.</p>
 */
public final class AfkGuard implements Listener {

    /** Quello che sappiamo di un giocatore in questo momento. */
    private static final class Stato {
        long ultimoInput;              // ultima azione volontaria riconosciuta
        boolean afk;
        long afkDa;
        /** Intervalli fra i click, per riconoscere le cadenze impossibili. */
        final Deque<Long> click = new ArrayDeque<>();
        long ultimoClick;
        long ultimaSegnalazione;
        /** Posizioni viste di recente: poche e ripetute = giro chiuso. */
        final Deque<String> posizioni = new ArrayDeque<>();
    }

    private final JavaPlugin plugin;
    private final Detector detector;
    private final Messages messages;

    private final boolean active;
    private final long inattivoDopo;
    private final boolean nienteGuadagni;
    private final int raggioSpawn;
    private final boolean huntActive;
    private final int clickMinimi;
    private final double maxSkewMs;
    private final long minDuration;

    private final Map<UUID, Stato> stati = new ConcurrentHashMap<>();

    public AfkGuard(JavaPlugin plugin, Detector detector, ConfigurationSection cfg, Messages messages) {
        this.plugin = plugin;
        this.detector = detector;
        this.messages = messages;
        this.active = cfg == null || cfg.getBoolean("attivo", true);
        this.inattivoDopo = (cfg == null ? 10 : Math.max(1, cfg.getInt("minuti-inattivo", 10))) * 60_000L;
        this.nienteGuadagni = cfg == null || cfg.getBoolean("niente-guadagni", true);
        this.raggioSpawn = cfg == null ? 24 : Math.max(8, cfg.getInt("raggio-spawn", 24));
        this.huntActive = cfg == null || cfg.getBoolean("dispositivi/attivo", true);
        this.clickMinimi = cfg == null ? 120 : Math.max(30, cfg.getInt("dispositivi/click-minimi", 120));
        this.maxSkewMs = cfg == null ? 12 : cfg.getDouble("dispositivi/scarto-massimo-ms", 12);
        this.minDuration = (cfg == null ? 2 : Math.max(1, cfg.getInt("dispositivi/durata-minima-minuti", 2)))
                * 60_000L;
    }

    /** Da lanciare all'avvio: aggiorna lo stato AFK di tutti, ogni 20 secondi. */
    public void start() {
        if (!active) {
            return;
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshStates, 400L, 400L);
    }

    // ------------------------------------------------------------------ attivita'

    /**
     * Cosa conta come "sono qui davvero": muoversi di posizione, girarsi, interagire.
     * Il movimento e' l'evento piu' frequente del server, quindi qui dentro non si fa altro
     * che confrontare due numeri.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void suMovimento(PlayerMoveEvent e) {
        if (!active || e.getTo() == null) {
            return;
        }
        Location da = e.getFrom();
        Location a = e.getTo();
        boolean spostato = da.getBlockX() != a.getBlockX() || da.getBlockY() != a.getBlockY()
                || da.getBlockZ() != a.getBlockZ();
        boolean girato = Math.abs(da.getYaw() - a.getYaw()) > 5f || Math.abs(da.getPitch() - a.getPitch()) > 5f;
        if (!spostato && !girato) {
            return;
        }
        Stato s = stato(e.getPlayer());
        segnaAttivita(e.getPlayer(), s);
        if (spostato) {
            rememberPosition(s, a);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void suInterazione(PlayerInteractEvent e) {
        if (active) {
            segnaAttivita(e.getPlayer(), stato(e.getPlayer()));
        }
    }

    /** Ogni colpo di braccio: e' da qui che si riconoscono gli autoclicker. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void suColpo(PlayerAnimationEvent e) {
        if (!active) {
            return;
        }
        Player p = e.getPlayer();
        Stato s = stato(p);
        segnaAttivita(p, s);
        if (huntActive) {
            recordClick(p, s);
        }
    }

    @EventHandler
    public void suUscita(PlayerQuitEvent e) {
        stati.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ niente guadagni

    /**
     * I mostri non nascono attorno a chi e' fermo. Si guarda se nel raggio c'e' <b>almeno un</b>
     * giocatore sveglio: se c'e', lo spawn e' suo e non si tocca — altrimenti basterebbe un AFK
     * di passaggio per rovinare la serata a chi sta giocando li' accanto.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void suNascita(CreatureSpawnEvent e) {
        if (!active || !nienteGuadagni) {
            return;
        }
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                || !(e.getEntity() instanceof Monster)) {
            return;
        }
        boolean qualcunoSveglio = false;
        boolean qualcunoAfk = false;
        for (Entity vicino : e.getLocation().getNearbyEntities(raggioSpawn, raggioSpawn, raggioSpawn)) {
            if (!(vicino instanceof Player p)) {
                continue;
            }
            if (eAfk(p)) {
                qualcunoAfk = true;
            } else {
                qualcunoSveglio = true;
                break;
            }
        }
        if (qualcunoAfk && !qualcunoSveglio) {
            e.setCancelled(true);
        }
    }

    /** Quello che cade per terra non arriva addosso a chi e' fermo. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void suRaccolta(EntityPickupItemEvent e) {
        if (active && nienteGuadagni && e.getEntity() instanceof Player p && eAfk(p)) {
            e.setCancelled(true);
        }
    }

    /** E nemmeno l'esperienza. */
    @EventHandler(priority = EventPriority.HIGH)
    public void suEsperienza(PlayerExpChangeEvent e) {
        if (active && nienteGuadagni && eAfk(e.getPlayer())) {
            e.setAmount(0);
        }
    }

    // ------------------------------------------------------------------ dispositivi

    /**
     * Un essere umano non clicca a intervalli costanti. Una macro si': la sua firma e' uno
     * scarto fra i click di pochi millisecondi, mantenuto per minuti. Si guarda quello, e solo
     * quando i click sono abbastanza da non essere una coincidenza.
     */
    private void recordClick(Player p, Stato s) {
        long now = System.currentTimeMillis();
        if (s.ultimoClick > 0) {
            long intervallo = now - s.ultimoClick;
            if (intervallo > 0 && intervallo < 2000) {
                s.click.addLast(intervallo);
                while (s.click.size() > 400) {
                    s.click.removeFirst();
                }
            } else {
                s.click.clear();   // pausa lunga: la serie ricomincia
            }
        }
        s.ultimoClick = now;

        if (s.click.size() < clickMinimi) {
            return;
        }
        long duration = 0;
        for (long i : s.click) {
            duration += i;
        }
        if (duration < minDuration) {
            return;
        }
        if (now - s.ultimaSegnalazione < 10 * 60_000L) {
            return;
        }

        double media = duration / (double) s.click.size();
        double somma = 0;
        for (long i : s.click) {
            somma += (i - media) * (i - media);
        }
        double scarto = Math.sqrt(somma / s.click.size());

        if (scarto > maxSkewMs) {
            return;
        }
        s.ultimaSegnalazione = now;

        String prove = "Cadenza dei click di " + p.getName() + "\n"
                + "Click osservati di fila: " + s.click.size() + '\n'
                + "Durata della serie: " + (duration / 1000) + " secondi\n"
                + "Intervallo medio: " + Math.round(media) + " ms\n"
                + "Scarto fra i click: " + Math.round(scarto * 10) / 10.0 + " ms "
                + "(soglia: " + maxSkewMs + " ms)\n"
                + "Posizione: " + p.getWorld().getName() + ' ' + p.getLocation().getBlockX() + ", "
                + p.getLocation().getBlockY() + ", " + p.getLocation().getBlockZ() + "\n\n"
                + "Cosa dimostra: una mano umana non tiene un intervallo costante al millisecondo "
                + "per minuti interi. Uno scarto cosi' basso su una serie cosi' lunga si ottiene "
                + "solo con una macro, un autoclicker o un peso appoggiato sul mouse.";

        detector.rileva(p.getUniqueId(), p.getName(), "afk.elusione", "afk", prove);
    }

    /**
     * Le posizioni recenti. Chi gira in barca su un cerchio, o viene spinto avanti e indietro da
     * un pistone, passa sempre per gli stessi due o tre blocchi: il movimento c'e' ma non porta
     * da nessuna parte. Per ora la si misura e basta — diventa una prova solo insieme ad altro.
     */
    private void rememberPosition(Stato s, Location l) {
        String key = l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
        if (!key.equals(s.posizioni.peekLast())) {
            s.posizioni.addLast(key);
            while (s.posizioni.size() > 200) {
                s.posizioni.removeFirst();
            }
        }
    }

    // ------------------------------------------------------------------ stato

    private Stato stato(Player p) {
        return stati.computeIfAbsent(p.getUniqueId(), k -> {
            Stato s = new Stato();
            s.ultimoInput = System.currentTimeMillis();
            return s;
        });
    }

    private void segnaAttivita(Player p, Stato s) {
        s.ultimoInput = System.currentTimeMillis();
        if (s.afk) {
            s.afk = false;
            p.sendMessage(Text.msg(messages.get(p, "afk.returned")));
        }
    }

    /** Il giro periodico: chi non tocca niente da abbastanza tempo passa in stato fermo. */
    private void refreshStates() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Stato s = stato(p);
            if (!s.afk && now - s.ultimoInput >= inattivoDopo) {
                s.afk = true;
                s.afkDa = now;
                if (nienteGuadagni) {
                    p.sendMessage(Text.msg(messages.get(p, "afk.idle")));
                }
            }
        }
    }

    /** True se quel giocatore e' fermo adesso. */
    public boolean eAfk(Player p) {
        Stato s = stati.get(p.getUniqueId());
        return s != null && s.afk;
    }

    /** Da quanto e' fermo, in minuti. 0 se non lo e'. */
    public long frozenMinutes(Player p) {
        Stato s = stati.get(p.getUniqueId());
        return s == null || !s.afk ? 0 : (System.currentTimeMillis() - s.afkDa) / 60_000L;
    }
}
