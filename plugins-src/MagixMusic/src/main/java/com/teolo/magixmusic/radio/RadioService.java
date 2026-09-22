package com.teolo.magixmusic.radio;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Radio musicale sincronizzata allo SPAWN: un "jukebox" invisibile che suona a ciclo i dischi di
 * Minecraft (config di MagixMusic). Il server fa da orologio comune, quindi tutti i giocatori nella zona
 * spawn sentono lo STESSO brano nello STESSO momento; il suono è ancorato allo spawn e cala con la
 * distanza (come un altoparlante), così negli altri mondi o lontano dallo spawn non si sente.
 *
 * <p>Ogni giocatore regola il proprio volume o spegne la radio per sé con {@code /radio}: la preferenza
 * (0-100, assente = default, 0 = spenta) sta in {@link VolumeStore}.
 *
 * <p><b>Limite di Minecraft</b>: un suono parte sempre dall'inizio, non si può "riprendere" a metà.
 * Quindi chi entra a brano già iniziato ({@code play-on-join}) lo sente dall'inizio, non dal secondo in
 * corso; al primo cambio di brano si riallinea con tutti (il boundary manda a tutti lo stesso stop+play).
 */
public final class RadioService {

    /** Un brano della scaletta: id del suono e durata in secondi (per far partire il successivo in tempo). */
    private record Track(String sound, int seconds) {}

    private final JavaPlugin plugin;
    private final VolumeStore volumes;

    // Impostazioni lette dal config (a ogni start/reload).
    private boolean enabled;
    private String worldName;
    private int radius;
    private SoundCategory category;
    private float pitch;
    private boolean playOnJoin;
    private int defaultVolume;
    private int volumeStep;
    private final List<Track> playlist = new ArrayList<>();

    // Stato di riproduzione (solo main thread).
    private int index;
    private String currentSound;     // brano in onda ORA, oppure null se la radio non sta suonando
    private int taskId = -1;         // task che farà partire il brano successivo
    private long currentStartMillis; // quando è partito il brano corrente (per tempo trascorso/rimanente)
    private int currentDurationSeconds; // durata del brano corrente in secondi

    public RadioService(JavaPlugin plugin, VolumeStore volumes) {
        this.plugin = plugin;
        this.volumes = volumes;
    }

    // ------------------------------------------------------------- ciclo di vita

    /** Rilegge il config e (ri)avvia la radio dall'inizio della scaletta. Idempotente: prima ferma tutto. */
    public void start() {
        stopPlayback();
        loadConfig();
        if (!enabled || playlist.isEmpty()) return;
        if (center() == null) {
            plugin.getLogger().warning("[Radio] mondo '" + worldName + "' non trovato: radio inattiva finché non è caricato.");
            return;
        }
        index = 0;
        playCurrentAndScheduleNext();
    }

    /** Lo chiama {@code /radio reload}: stessa cosa di {@link #start()}, nome esplicito per chi legge. */
    public void reload() { start(); }

    /** Spegne la radio e taglia la musica a chi la sta sentendo (allo spegnimento del plugin o via reload). */
    public void shutdown() {
        if (currentSound != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (worldName != null && p.getWorld().getName().equals(worldName)) stopFor(p);
            }
        }
        stopPlayback();
    }

    private void stopPlayback() {
        if (taskId != -1) { Bukkit.getScheduler().cancelTask(taskId); taskId = -1; }
        currentSound = null;
    }

    private void loadConfig() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("enabled", true);
        worldName = c.getString("world", "world");
        radius = c.getInt("radius", 80);
        pitch = (float) c.getDouble("pitch", 1.0);
        playOnJoin = c.getBoolean("play-on-join", true);
        defaultVolume = clampVolume(c.getInt("default-volume", 70));
        volumeStep = Math.max(1, c.getInt("volume-step", 10));
        // Categoria audio Bukkit da stringa: RECORDS (jukebox) o MUSIC. valueOf per non elencare i valori
        // nel codice (li documenta il commento del config); qualsiasi cosa strana ricade su RECORDS.
        SoundCategory cat = SoundCategory.RECORDS;
        try { cat = SoundCategory.valueOf(c.getString("sound-category", "records").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ignored) {}
        category = cat;

        playlist.clear();
        for (Map<?, ?> row : c.getMapList("playlist")) {
            Object sound = row.get("sound");
            Object seconds = row.get("seconds");
            if (sound == null || seconds == null) continue;
            int secs;
            try { secs = Integer.parseInt(String.valueOf(seconds).trim()); }
            catch (NumberFormatException e) { continue; }
            if (secs <= 0) continue;
            playlist.add(new Track(String.valueOf(sound).trim(), secs));
        }
    }

    // ------------------------------------------------------------- riproduzione

    /** Manda il brano corrente a tutti i giocatori nella zona e programma il passaggio al successivo. */
    private void playCurrentAndScheduleNext() {
        Track t = playlist.get(index);
        broadcastTrack(t.sound());
        currentSound = t.sound();
        currentStartMillis = System.currentTimeMillis();
        currentDurationSeconds = t.seconds();
        taskId = Bukkit.getScheduler().runTaskLater(plugin, this::advance, 20L * t.seconds()).getTaskId();
    }

    private void advance() {
        if (playlist.isEmpty()) { stopPlayback(); return; }
        index = (index + 1) % playlist.size();
        playCurrentAndScheduleNext();
    }

    /**
     * Salta al brano successivo SUBITO, per tutti (è una radio unica). Azione globale: il comando la
     * riserva allo staff. Non fa nulla se la radio è spenta o senza scaletta.
     */
    public void skip() {
        if (!enabled || playlist.isEmpty()) return;
        if (taskId != -1) { Bukkit.getScheduler().cancelTask(taskId); taskId = -1; }
        index = (index + 1) % playlist.size();
        playCurrentAndScheduleNext();
    }

    /**
     * Ferma il brano precedente e fa partire quello nuovo, nello stesso tick, per tutti i giocatori nella
     * zona: è così che la radio resta sincronizzata (stesso brano, stesso secondo) e che l'eventuale coda
     * di un brano partito in ritardo per un nuovo arrivato viene tagliata al boundary.
     */
    private void broadcastTrack(String newSound) {
        Location center = center();
        if (center == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!inZone(p)) continue;
            if (currentSound != null) p.stopSound(currentSound, category);
            if (effectiveVolume(p.getUniqueId()) > 0) playAt(p, center, newSound);
        }
    }

    /** Suona {@code sound} per {@code p}, ancorato allo spawn, al volume personale del giocatore. */
    private void playAt(Player p, Location center, String sound) {
        try { p.playSound(center, sound, category, volumeFloat(p.getUniqueId()), pitch); }
        catch (Exception ignored) {}
    }

    // ------------------------------------------------------------- API per il comando e il join

    /**
     * Fa sentire SUBITO il brano in onda a un giocatore (dall'inizio: vedi limite in classe), se è nella
     * zona e non ha la radio spenta. La usano {@code /radio on|up|volume} (feedback immediato) e il
     * listener del join. Ferma prima l'eventuale copia già in corso per non sovrapporla.
     */
    public void refreshFor(Player p) {
        if (currentSound == null) return;
        if (!inZone(p) || effectiveVolume(p.getUniqueId()) <= 0) return;
        Location center = center();
        if (center == null) return;
        p.stopSound(currentSound, category);
        playAt(p, center, currentSound);
    }

    /** Taglia la musica a un giocatore (quando spegne la radio con {@code /radio off}). */
    public void stopFor(Player p) {
        if (currentSound != null) p.stopSound(currentSound, category);
    }

    /** true se al giocatore la radio, in questo momento, arriverebbe: usato dal listener del join. */
    public boolean wouldPlayFor(Player p) {
        return enabled && currentSound != null && inZone(p) && effectiveVolume(p.getUniqueId()) > 0;
    }

    public boolean isPlayOnJoin() { return playOnJoin; }
    public boolean isEnabled() { return enabled; }
    public int defaultVolume() { return defaultVolume; }
    public int volumeStep() { return volumeStep; }

    /** Nome "carino" del brano in onda per i messaggi (es. {@code music_disc.cat} -> {@code Cat}); null se nulla. */
    public String currentTrackName() {
        return prettyName(currentSound);
    }

    /** Nome "carino" del brano che verrà dopo quello in onda; null se la radio non sta suonando. */
    public String nextTrackName() {
        if (currentSound == null || playlist.isEmpty()) return null;
        return prettyName(playlist.get((index + 1) % playlist.size()).sound());
    }

    /** Secondi trascorsi del brano in onda (0 se non suona), limitati alla durata del brano. */
    public int elapsedSeconds() {
        if (currentSound == null) return 0;
        long e = (System.currentTimeMillis() - currentStartMillis) / 1000L;
        return (int) Math.max(0, Math.min(currentDurationSeconds, e));
    }

    /** Durata del brano in onda in secondi (0 se non suona). */
    public int durationSeconds() { return currentSound == null ? 0 : currentDurationSeconds; }

    /** Quanti brani ha la scaletta. */
    public int trackCount() { return playlist.size(); }

    /** Posizione del brano in onda nella scaletta, da 1; 0 se non suona. */
    public int currentIndex() { return currentSound == null || playlist.isEmpty() ? 0 : index + 1; }

    /** Da {@code music_disc.cat} (o {@code minecraft:music_disc.cat}) a {@code Cat}. */
    private static String prettyName(String sound) {
        if (sound == null) return null;
        String s = sound;
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        s = s.replace('_', ' ').trim();
        if (s.isEmpty()) return sound;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------- calcoli interni

    /** Volume effettivo 0-100 del giocatore: il suo salvato, o il default del config se non l'ha mai toccato. */
    public int effectiveVolume(UUID u) {
        Integer saved = volumes.get(u);
        return clampVolume(saved == null ? defaultVolume : saved);
    }

    /** Volume da passare a playSound: la percentuale personale scalata sulla portata, così al 100% la
     *  musica arriva fino al bordo della zona (volume Minecraft ~ 16 blocchi per unità). */
    private float volumeFloat(UUID u) {
        float base = Math.max(1.0f, radius / 16.0f);
        return effectiveVolume(u) / 100.0f * base;
    }

    private boolean inZone(Player p) {
        if (!enabled) return false;
        Location center = center();
        if (center == null) return false;
        if (!p.getWorld().getName().equals(worldName)) return false;
        if (radius <= 0) return true;
        double dx = p.getLocation().getX() - center.getX();
        double dz = p.getLocation().getZ() - center.getZ();
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    private Location center() {
        World w = Bukkit.getWorld(worldName);
        return w == null ? null : w.getSpawnLocation();
    }

    private static int clampVolume(int v) { return Math.max(0, Math.min(100, v)); }
}
