package com.teolo.magixtime.time;

import com.teolo.magixtime.MagixTime;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Tiene l'ora dei mondi gestiti allineata all'orologio reale.
 *
 * Mezzogiorno reale = tick 6000, mezzanotte = 18000, alba (tick 0) = le 06:00.
 *
 * Se qualcuno cambia l'ora dall'esterno (/day, /time set, un altro plugin) il
 * plugin NON se la riprende all'istante: lascia valere il cambio per
 * time.override-seconds e poi riporta il sole in avanti fino a riagganciare
 * l'ora vera, alla velocita' di time.catchup-speed. Cosi' non c'e' mai un
 * braccio di ferro fra due sorgenti, che a schermo si vedrebbe come il sole e
 * la luna che saltano avanti e indietro.
 */
public final class TimeSync {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final MagixTime plugin;
    private BukkitTask task;
    private BukkitTask catchupTask;
    private boolean paused;

    /** Ultimo valore scritto da noi, per mondo: serve a riconoscere i cambi altrui. */
    private final Map<String, Long> written = new HashMap<>();
    /** Fine dell'override esterno (millis), per mondo. */
    private final Map<String, Long> overrideUntil = new HashMap<>();
    /** Mondi che stanno recuperando il ritardo con il sole accelerato. */
    private final Set<String> catchingUp = new HashSet<>();
    /** Mondi che condividono l'orologio con un altro mondo: non li gestiamo direttamente. */
    private final Set<String> linkedClock = new HashSet<>();

    /** Esito reale della gamerule: se resta true il client continua a far scorrere il tempo da solo. */
    private boolean daylightCycleOff;

    public TimeSync(MagixTime plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------- ciclo

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("time.enabled", true)) return;
        applyGamerules();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, effectiveInterval());
    }

    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        if (catchupTask != null) { catchupTask.cancel(); catchupTask = null; }
        written.clear();
        overrideUntil.clear();
        catchingUp.clear();
        linkedClock.clear();
    }

    /**
     * Spegne doDaylightCycle e verifica che abbia davvero preso.
     *
     * E' il punto piu' importante di tutto il modulo: finche' la gamerule resta
     * attiva il CLIENT avanza il tempo per conto suo (20 tick al secondo) e ogni
     * nostro aggiornamento lo riporta indietro al valore vero. A schermo si vede
     * la luna che va avanti e indietro senza fermarsi mai.
     */
    public void applyGamerules() {
        if (!plugin.getConfig().getBoolean("time.manage-gamerule", true)) {
            daylightCycleOff = false;
            return;
        }
        boolean allOff = true;
        for (World w : plugin.managedWorlds()) {
            w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            Boolean value = w.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE);
            if (value == null || value) {
                allOff = false;
                plugin.getLogger().warning("Non sono riuscito a spegnere doDaylightCycle nel mondo '"
                        + w.getName() + "': il client continuera' a far scorrere il tempo da solo. "
                        + "Attivo l'allineamento a ogni tick per compensare.");
            }
        }
        daylightCycleOff = allOff;
    }

    /**
     * Se la gamerule non si e' potuta spegnere, il client va corretto ad ogni tick:
     * lo scarto resta di 1 tick e diventa invisibile, invece dei 20 che si vedono
     * aggiornando una volta al secondo.
     */
    public long effectiveInterval() {
        long configured = Math.max(1, plugin.getConfig().getInt("time.update-interval-ticks", 20));
        return daylightCycleOff ? configured : 1L;
    }

    private void tick() {
        if (paused) return;
        long now = System.currentTimeMillis();
        for (World w : plugin.managedWorlds()) handleWorld(w, now);
    }

    private void handleWorld(World w, long now) {
        String name = w.getName();
        ensureGamerule(w);
        if (linkedClock.contains(name)) return; // l'ora gli arriva dal mondo che possiede il clock
        if (catchingUp.contains(name)) return;  // ci pensa il task di recupero

        Long until = overrideUntil.get(name);
        if (until != null) {
            if (now < until) return;      // il cambio esterno vale ancora
            overrideUntil.remove(name);
            startCatchup(w);              // scaduto: il sole torna in avanti fino all'ora vera
            return;
        }

        Long last = written.get(name);
        if (last != null && Math.abs(w.getFullTime() - last) > tolerance()) {
            noteExternalChange(w, now);
            return;
        }
        alignNow(w);
    }

    /** Rimette doDaylightCycle a false se un altro plugin l'ha riaccesa, senza rifare i log. */
    private void ensureGamerule(World w) {
        if (!plugin.getConfig().getBoolean("time.manage-gamerule", true)) return;
        Boolean value = w.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE);
        if (value != null && value) w.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
    }

    /** Lo scarto naturale fra due aggiornamenti; oltre questo il cambio e' di qualcun altro. */
    private long tolerance() {
        return effectiveInterval() + 60L;
    }

    private void noteExternalChange(World w, long now) {
        int seconds = Math.max(0, plugin.getConfig().getInt("time.override-seconds", 60));
        if (seconds == 0) { alignNow(w); return; }
        overrideUntil.put(w.getName(), now + seconds * 1000L);
        written.remove(w.getName());
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("Ora cambiata dall'esterno nel mondo '" + w.getName()
                    + "': la lascio valere per " + seconds + "s, poi recupero.");
        }
    }

    // ------------------------------------------------------------- recupero graduale

    /** Avvia il rientro morbido: il sole corre in avanti finche' non raggiunge l'ora reale. */
    private void startCatchup(World w) {
        int speed = plugin.getConfig().getInt("time.catchup-speed", 60);
        if (speed <= 0) { alignNow(w); return; }   // 0 = rientro secco, senza animazione
        catchingUp.add(w.getName());
        if (catchupTask == null) {
            // Ogni tick, altrimenti il sole avanzerebbe a scatti da centinaia di tick.
            catchupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::catchupTick, 1L, 1L);
        }
    }

    private void catchupTick() {
        int speed = Math.max(1, plugin.getConfig().getInt("time.catchup-speed", 60));
        int target = currentMinecraftTicks();

        Iterator<String> it = catchingUp.iterator();
        while (it.hasNext()) {
            World w = Bukkit.getWorld(it.next());
            if (w == null || !plugin.isManaged(w)) { it.remove(); continue; }
            long cur = w.getFullTime();
            // Sempre in AVANTI: un sole che torna indietro si nota, uno che corre no.
            long delta = Math.floorMod(target - Math.floorMod(cur, 24000L), 24000L);
            if (delta <= speed) {
                alignNow(w);
                it.remove();
                continue;
            }
            write(w, cur + speed);
        }
        if (catchingUp.isEmpty() && catchupTask != null) {
            catchupTask.cancel();
            catchupTask = null;
        }
    }

    // ------------------------------------------------------------- scrittura

    /** Riallinea subito tutti i mondi gestiti, annullando override e recuperi. Ritorna i mondi toccati. */
    public int sync() {
        overrideUntil.clear();
        catchingUp.clear();
        if (catchupTask != null) { catchupTask.cancel(); catchupTask = null; }
        int count = 0;
        for (World w : plugin.managedWorlds()) { alignNow(w); count++; }
        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger().info("Sync ora: " + realTime() + " -> tick " + currentMinecraftTicks()
                    + " su " + count + " mondi.");
        }
        return count;
    }

    /** Porta il mondo esattamente all'ora reale. */
    private void alignNow(World w) {
        int ticks = currentMinecraftTicks();
        if (plugin.getConfig().getBoolean("time.sync-day-counter", false)) {
            write(w, dayNumber() * 24000L + ticks);
            return;
        }
        // Il contatore dei giorni del mondo resta quello suo: qui si sposta solo
        // l'ora, aggiungendo un giorno quando si passa il tick 24000 (le 06:00 reali).
        long full = w.getFullTime();
        long day = Math.floorDiv(full, 24000L);
        long timeOfDay = Math.floorMod(full, 24000L);
        long target = day * 24000L + ticks;
        if (timeOfDay - ticks > 12000L) target += 24000L;        // superata la mezzanotte MC
        else if (ticks - timeOfDay > 12000L) target -= 24000L;   // orologio del mondo avanti
        write(w, target);
    }

    /**
     * Scrive l'ora e verifica cosa e' rimasto davvero nel mondo.
     *
     * Le dimensioni custom che vivono dentro il save principale (world/dimensions/...)
     * NON possiedono il proprio orologio: lo condividono con l'overworld. Scriverci
     * sopra e' inutile, e trattarle come mondi indipendenti significa avere due
     * scrittori sullo stesso clock e un mucchio di falsi "cambio dall'esterno".
     * Qui si registra sempre il valore REALE dopo la scrittura, e i mondi che non
     * accettano il valore vengono tolti dalla gestione dell'ora.
     */
    private void write(World w, long fullTime) {
        if (w.getFullTime() != fullTime) w.setFullTime(fullTime);
        long actual = w.getFullTime();
        if (actual != fullTime && linkedClock.add(w.getName())) {
            plugin.getLogger().info("Il mondo '" + w.getName() + "' condivide l'orologio con un altro "
                    + "mondo (dimensione dello stesso save): lo tolgo dalla gestione dell'ora, "
                    + "l'ora gli arriva comunque dal mondo principale.");
        }
        written.put(w.getName(), actual);
    }

    // ------------------------------------------------------------- conversioni

    /** Ora reale (fuso + offset del config) convertita in tick di Minecraft 0-23999. */
    public int currentMinecraftTicks() {
        int secondsOfDay = plugin.nowInZone().toLocalTime().toSecondOfDay();
        long ticks = Math.round(secondsOfDay * 24000.0 / 86400.0) + 18000L;
        return (int) Math.floorMod(ticks, 24000L);
    }

    /** Giorni interi trascorsi da time.day-counter-origin, con lo stacco alle 06:00. */
    private long dayNumber() {
        ZonedDateTime now = plugin.nowInZone();
        LocalDate date = now.toLocalDate();
        if (now.toLocalTime().getHour() < 6) date = date.minusDays(1); // il "giorno" MC parte all'alba
        LocalDate origin;
        try {
            origin = LocalDate.parse(plugin.getConfig().getString("time.day-counter-origin", "2026-01-01"));
        } catch (Exception e) {
            origin = LocalDate.of(2026, 1, 1);
        }
        return Math.max(0, date.toEpochDay() - origin.toEpochDay());
    }

    /** Ora di gioco corrente in formato HH:mm (derivata dai tick del mondo). */
    public static String formatTicks(long ticks) {
        long t = Math.floorMod(ticks + 6000L, 24000L); // tick 0 = 06:00
        long minutes = t * 1440L / 24000L;
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    public String realTime() { return plugin.nowInZone().format(HHMMSS); }
    public String realTimeShort() { return plugin.nowInZone().format(HHMM); }
    public String realDate() { return plugin.nowInZone().format(DATE); }

    public boolean isPaused() { return paused; }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (paused) {
            overrideUntil.clear();
            catchingUp.clear();
            if (catchupTask != null) { catchupTask.cancel(); catchupTask = null; }
        }
    }

    public boolean isDaylightCycleOff() { return daylightCycleOff; }

    /** Secondi mancanti alla fine dell'override esterno sul mondo (0 = nessun override). */
    public long overrideLeft(World w) {
        if (w == null) return 0;
        Long until = overrideUntil.get(w.getName());
        if (until == null) return 0;
        return Math.max(0, (until - System.currentTimeMillis()) / 1000L);
    }

    public boolean isCatchingUp(World w) {
        return w != null && catchingUp.contains(w.getName());
    }

    /** true se il mondo condivide l'orologio con un altro (dimensione dello stesso save). */
    public boolean isLinked(World w) {
        return w != null && linkedClock.contains(w.getName());
    }
}
