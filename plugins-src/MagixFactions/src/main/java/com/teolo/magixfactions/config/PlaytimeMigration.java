package com.teolo.magixfactions.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sposta da solo il tempo di gioco minimo per fondare una fazione dalla condizione libera
 * {@code create-cost.placeholders: ["%magixessentials_playtime% >= N"]} alla chiave dedicata
 * {@code create-cost.min-playtime-seconds: N}, che il tutorial sa raccontare.
 * <p>
 * Gira a ogni avvio e reload subito dopo {@code ConfigAlign} (che ha gia' aggiunto la chiave nuova):
 * se la condizione c'e' e la chiave vale 0 la travasa e la toglie dalla lista; altrimenti non tocca
 * niente. Lavora sulle righe del file, come ConfigAlign, cosi' commenti e ordine restano intatti, e
 * fa la copia di scorta in {@code .bak/MagixFactions/} prima di scrivere. Cosi' lo staff puo' anche
 * continuare a scrivere la condizione in {@code placeholders}: al primo reload diventa la chiave.
 */
public final class PlaytimeMigration {

    private static final Pattern CONDITION = Pattern.compile(
            "%magixessentials_playtime%\\s*(>=|>)\\s*(\\d+)");
    private static final Pattern MIN_LINE = Pattern.compile("^(\\s+min-playtime-seconds:\\s*)(\\S+)(.*)$");
    private static final Pattern LIST_LINE = Pattern.compile("^(\\s+placeholders:\\s*)\\[(.*)](.*)$");

    private PlaytimeMigration() {}

    public static void run(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.isFile()) return;
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            long seconds = migrate(lines);
            if (seconds < 0) return;

            File bak = bakDir(plugin);
            Files.createDirectories(bak.toPath());
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            Files.copy(file.toPath(), new File(bak, "config.yml.bak-" + stamp).toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
            plugin.getLogger().info("[Config] create-cost: la condizione sul tempo di gioco è diventata "
                    + "min-playtime-seconds: " + seconds + " (tolta da placeholders).");
        } catch (Exception e) {
            plugin.getLogger().warning("[Config] spostamento del tempo di gioco in create-cost non riuscito ("
                    + e.getClass().getSimpleName() + "): il file non è stato toccato.");
        }
    }

    /** Cambia {@code lines} sul posto; @return i secondi travasati, o -1 se non c'era niente da fare. */
    static long migrate(List<String> lines) {
        int start = -1, end = lines.size();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (start < 0) {
                if (l.startsWith("create-cost:")) start = i;
            } else if (!l.isBlank() && !Character.isWhitespace(l.charAt(0)) && !l.startsWith("#")) {
                end = i;
                break;
            }
        }
        if (start < 0) return -1;

        int minIdx = -1;
        for (int i = start + 1; i < end; i++) if (MIN_LINE.matcher(lines.get(i)).matches()) minIdx = i;
        if (minIdx < 0) return -1;
        Matcher min = MIN_LINE.matcher(lines.get(minIdx));
        min.matches();
        if (!"0".equals(min.group(2))) return -1;   // scelto a mano: la condizione resta dov'e'

        long seconds = -1;
        int listIdx = -1;
        String newListLine = null;
        List<Integer> drop = new ArrayList<>();
        for (int i = start + 1; i < end && seconds < 0; i++) {
            String l = lines.get(i);
            Matcher inline = LIST_LINE.matcher(l);
            if (inline.matches()) {
                // Forma in riga: placeholders: ["a", "b"]
                List<String> kept = new ArrayList<>();
                for (String entry : inline.group(2).split(",")) {
                    Matcher c = CONDITION.matcher(entry);
                    if (seconds < 0 && c.find()) seconds = seconds(c);
                    else if (!entry.isBlank()) kept.add(entry.trim());
                }
                if (seconds >= 0) {
                    listIdx = i;
                    newListLine = inline.group(1) + "[" + String.join(", ", kept) + "]" + inline.group(3);
                }
            } else if (l.trim().startsWith("- ") && i > start + 1) {
                // Forma a elenco: placeholders:\n    - "a"
                Matcher c = CONDITION.matcher(l);
                if (c.find()) { seconds = seconds(c); drop.add(i); }
            }
        }
        if (seconds < 0) return -1;

        lines.set(minIdx, min.group(1) + seconds + min.group(3));
        if (listIdx >= 0) lines.set(listIdx, newListLine);
        for (int k = drop.size() - 1; k >= 0; k--) lines.remove((int) drop.get(k));
        return seconds;
    }

    private static long seconds(Matcher c) {
        long n = Long.parseLong(c.group(2));
        return ">".equals(c.group(1)) ? n + 1 : n;
    }

    /** Stessa cartella delle copie di ConfigAlign: {@code plugins/} sostituita con {@code .bak/}. */
    private static File bakDir(JavaPlugin plugin) {
        File dataFolder = plugin.getDataFolder().getAbsoluteFile();
        File pluginsDir = dataFolder.getParentFile();
        File serverRoot = pluginsDir == null ? null : pluginsDir.getParentFile();
        if (serverRoot == null) return dataFolder;
        return new File(new File(serverRoot, ".bak"), dataFolder.getName());
    }
}
