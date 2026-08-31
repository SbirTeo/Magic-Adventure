package com.teolo.magixtime.season;

import org.bukkit.configuration.ConfigurationSection;

import java.time.MonthDay;

/** Una stagione come descritta in config.yml (seasons.list.&lt;key&gt;). */
public final class SeasonDef {

    private final String key;
    private final String display;
    private final MonthDay customStart;
    private final int rainChance;
    private final int thunderChance;
    private final int rainMinMinutes;
    private final int rainMaxMinutes;
    private final int clearMinMinutes;
    private final int clearMaxMinutes;
    private final boolean snowAccumulate;
    private final boolean snowMelt;

    public SeasonDef(String key, ConfigurationSection sec) {
        this.key = key;
        this.display = sec.getString("display", key);
        this.customStart = parseMonthDay(sec.getString("start", "01-01"));
        this.rainChance = clampPercent(sec.getInt("rain-chance", 40));
        this.thunderChance = clampPercent(sec.getInt("thunder-chance", 20));
        this.rainMinMinutes = Math.max(1, sec.getInt("rain-min-minutes", 20));
        this.rainMaxMinutes = Math.max(rainMinMinutes, sec.getInt("rain-max-minutes", 90));
        this.clearMinMinutes = Math.max(1, sec.getInt("clear-min-minutes", 60));
        this.clearMaxMinutes = Math.max(clearMinMinutes, sec.getInt("clear-max-minutes", 240));
        this.snowAccumulate = sec.getBoolean("snow-accumulate", false);
        this.snowMelt = sec.getBoolean("snow-melt", false);
    }

    /** "MM-DD" -> MonthDay; se il formato e' sbagliato ripiega sul 1 gennaio. */
    private static MonthDay parseMonthDay(String s) {
        try {
            String[] p = s.trim().split("-");
            return MonthDay.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
        } catch (Exception e) {
            return MonthDay.of(1, 1);
        }
    }

    private static int clampPercent(int v) { return Math.max(0, Math.min(100, v)); }

    public String key() { return key; }
    public String display() { return display; }
    public MonthDay customStart() { return customStart; }
    public int rainChance() { return rainChance; }
    public int thunderChance() { return thunderChance; }
    public int rainMinMinutes() { return rainMinMinutes; }
    public int rainMaxMinutes() { return rainMaxMinutes; }
    public int clearMinMinutes() { return clearMinMinutes; }
    public int clearMaxMinutes() { return clearMaxMinutes; }
    public boolean snowAccumulate() { return snowAccumulate; }
    public boolean snowMelt() { return snowMelt; }
}
