package com.teolo.magixmenus.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Il testo di un menu prima di finire sotto gli occhi di chi gioca.
 *
 * Tre passaggi, sempre in quest'ordine:
 * <ol>
 *   <li>le <b>variabili del menu</b> ({@code %pagina%}, {@code %arg_1%}, {@code %voce_nome%}):
 *       le conosce solo MagixMenus, e vanno risolte per prime perche' possono contenere a loro
 *       volta un placeholder di PlaceholderAPI;</li>
 *   <li>i <b>placeholder di PlaceholderAPI</b> ({@code %player_name%}, {@code %magixfactions_power%});</li>
 *   <li>i <b>colori</b> ({@code &a}, {@code &#C046E8}).</li>
 * </ol>
 *
 * L'ordine non e' un dettaglio: colorando per primo, un placeholder che restituisce un colore
 * verrebbe stampato per esteso.
 */
public final class Text {

    /** Qualcosa da risolvere: un placeholder %...% o una variabile {...}. */
    private static final Pattern DINAMICO = Pattern.compile("%[^%\s]+%");

    private static boolean papi;

    private Text() {
    }

    /** Chiamata all'avvio: se PlaceholderAPI non c'e', si salta il passaggio invece di fallire. */
    public static void rilevaPlaceholderApi() {
        papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    public static boolean placeholderApiPresente() {
        return papi;
    }

    /** Text pronto: variabili del menu, poi PlaceholderAPI, poi colori. */
    public static String applica(Player p, Map<String, String> variabili, String s) {
        return Colors.translate(grezzo(p, variabili, s));
    }

    /** Come {@link #applica} ma senza tradurre i colori: serve a chi confronta valori (i requisiti). */
    public static String grezzo(Player p, Map<String, String> variabili, String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (variabili != null && !variabili.isEmpty() && s.indexOf('%') >= 0) {
            for (Map.Entry<String, String> e : variabili.entrySet()) {
                s = s.replace("%" + e.getKey() + "%", e.getValue() == null ? "" : e.getValue());
            }
        }
        if (papi && s.indexOf('%') >= 0) {
            try {
                s = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(p, s);
            } catch (Throwable ignored) {
                // PlaceholderAPI presente ma in errore: meglio il testo grezzo che nessun menu.
            }
        }
        return s;
    }

    public static List<String> applica(Player p, Map<String, String> variabili, List<String> righe) {
        List<String> out = new ArrayList<>(righe.size());
        for (String r : righe) {
            // Un placeholder che restituisce piu' righe (una lore intera, un elenco) deve
            // diventare piu' righe di lore, non una riga sola con dentro degli "a capo".
            String risolto = applica(p, variabili, r);
            if (risolto.indexOf('\n') >= 0) {
                for (String pezzo : risolto.split("\n", -1)) {
                    out.add(pezzo);
                }
            } else {
                out.add(risolto);
            }
        }
        return out;
    }

    /**
     * Il testo cambia da solo nel tempo? Se non contiene niente di dinamico, l'item non va
     * ricostruito a ogni giro di aggiornamento: e' quello che tiene in piedi un menu con
     * {@code aggiornamento: 1} anche con cento giocatori collegati.
     */
    public static boolean dinamico(String s) {
        return s != null && DINAMICO.matcher(s).find();
    }

    public static boolean dinamico(List<String> righe) {
        if (righe == null) {
            return false;
        }
        for (String r : righe) {
            if (dinamico(r)) {
                return true;
            }
        }
        return false;
    }

    /** Numero letto da un testo gia' risolto: "12", "12.5", "1.234" (le virgole si ignorano). */
    public static Double numero(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim().replace(",", "");
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
