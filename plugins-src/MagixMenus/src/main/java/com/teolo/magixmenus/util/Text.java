package com.teolo.magixmenus.util;

import com.teolo.magixlanguage.api.MagixLanguageAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

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
    public static String apply(Player p, Map<String, String> variabili, String s) {
        return Colors.translate(raw(p, variabili, s));
    }

    /** Come {@link #applica} ma senza tradurre i colori: serve a chi confronta valori (i requisiti). */
    public static String raw(Player p, Map<String, String> variabili, String s) {
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

    public static List<String> apply(Player p, Map<String, String> variabili, List<String> rows) {
        List<String> out = new ArrayList<>(rows.size());
        for (String r : rows) {
            // Un placeholder che restituisce piu' righe (una lore intera, un elenco) deve
            // diventare piu' righe di lore, non una riga sola con dentro degli "a capo".
            String risolto = apply(p, variabili, r);
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

    public static boolean dinamico(List<String> rows) {
        if (rows == null) {
            return false;
        }
        for (String r : rows) {
            if (dinamico(r)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Il testo tradotto per il giocatore (se MagixLanguage lo conosce), altrimenti quello
     * italiano invariato. Va chiamato PRIMA di sostituire variabili/placeholder (vedi
     * {@link #apply}/{@link #raw}): la ricerca avviene sul testo con {@code %placeholder%}
     * ancora intatti, esattamente come li ha letti MagixLanguage scandendo il file del menu.
     *
     * <p>Usarlo solo per testo che un giocatore LEGGE (titolo, lore, un messaggio scritto dentro
     * un'azione): i campi tecnici (materiale, colore, testa, componenti, incantesimi...) non
     * passano mai da qui, altrimenti si rischierebbe di provare a "tradurre" un nome di materiale.
     */
    public static String translated(Player p, String italianText) {
        if (p == null || italianText == null || italianText.isBlank()) {
            return italianText;
        }
        MagixLanguageAPI api = magixLanguage();
        if (api == null) {
            return italianText;
        }
        try {
            String t = api.translatePhrase("MagixMenus", p, italianText);
            return t != null ? t : italianText;
        } catch (Throwable ignored) {
            return italianText;
        }
    }

    /** Il servizio di MagixLanguage se il plugin e' installato e attivo, altrimenti null: mai un'eccezione. */
    private static MagixLanguageAPI magixLanguage() {
        if (Bukkit.getPluginManager().getPlugin("MagixLanguage") == null) {
            return null;
        }
        try {
            RegisteredServiceProvider<MagixLanguageAPI> rsp =
                    Bukkit.getServicesManager().getRegistration(MagixLanguageAPI.class);
            return rsp != null ? rsp.getProvider() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Numero letto da un testo gia' risolto: "12", "12.5", "1.234" (le virgole si ignorano). */
    public static Double number(String s) {
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
