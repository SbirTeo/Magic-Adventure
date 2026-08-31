package com.teolo.magixguard.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Formattazione di date e durate per staff e dossier.
 *
 * Il fuso e' fissato a Europe/Rome: il VPS gira in UTC, ma un dossier che finisce in un
 * ricorso deve riportare gli orari come li ha vissuti il giocatore (stesso motivo per cui
 * MagixTime lavora su Europe/Rome).
 */
public final class Fmt {

    public static final ZoneId ZONE = ZoneId.of("Europe/Rome");

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(ZONE);
    private static final DateTimeFormatter DATE_TIME_SHORT =
            DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZONE);
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZONE);

    private Fmt() {}

    public static String dateTime(long epochMillis) {
        return epochMillis <= 0 ? "-" : DATE_TIME.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String shortDateTime(long epochMillis) {
        return epochMillis <= 0 ? "-" : DATE_TIME_SHORT.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String fileStamp(long epochMillis) {
        return FILE_STAMP.format(Instant.ofEpochMilli(epochMillis));
    }

    /** "3 g 4 h", "12 min", "45 s": durate leggibili senza fare conti. */
    public static String duration(long millis) {
        if (millis <= 0) return "-";
        Duration d = Duration.ofMillis(millis);
        long days = d.toDays();
        long hours = d.toHours() % 24;
        long minutes = d.toMinutes() % 60;
        long seconds = d.toSeconds() % 60;
        if (days > 0) return days + " g " + hours + " h";
        if (hours > 0) return hours + " h " + minutes + " min";
        if (minutes > 0) return minutes + " min";
        return seconds + " s";
    }

    /** Giorni (con decimali) trascorsi da un istante. */
    public static double daysSince(long epochMillis, long now) {
        return Math.max(0, (now - epochMillis) / 86_400_000.0);
    }
}
