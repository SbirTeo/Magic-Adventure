package com.teolo.magixcosmetics.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Traduzione colori: codici &, e esadecimali &#RRGGBB. */
public final class Colors {

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    private Colors() {}

    public static String translate(String s) {
        if (s == null) return "";
        Matcher m = HEX.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) rep.append('§').append(c);
            m.appendReplacement(sb, rep.toString());
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    /** Testo con codici & convertito in Component Adventure (titoli, action bar, nomi). */
    public static Component component(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(translate(s));
    }
}
