package com.teolo.magixlanguage.translate;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le righe di aiuto ("/f join &lt;fazione&gt; :: entra se invitato") hanno due parti: davanti a
 * "::" la SINTASSI del comando, dietro la descrizione. Solo la descrizione va al traduttore
 * automatico. La sintassi si traduce qui, parola per parola, con argument-glossary.yml: solo i
 * nomi degli argomenti dentro &lt; &gt; e [ ], mai il comando ne' le opzioni letterali.
 *
 * <p>Mandata a MyMemory, la sintassi usciva male in due modi, visti davvero: "&lt;fazione&gt;"
 * restava in italiano (protetto per intero, per non far mangiare gli spazi intorno) e
 * "[giocatore|clear]" diventava "[jugador|clear]" in un testo INGLESE. E un sottocomando tradotto
 * ("/f unirse") darebbe al giocatore un comando che non esiste.</p>
 */
public final class HelpSyntax {

    private static final Pattern LINE = Pattern.compile("^(\\s*/[^\\n]*?)(\\s*::\\s*)(.*)$", Pattern.DOTALL);
    /** Una parola, anche col trattino ("blocchi-per-pixel" e' una voce sola del glossario). */
    private static final Pattern WORD = Pattern.compile("[\\p{L}][\\p{L}\\p{N}]*(?:-[\\p{L}\\p{N}]+)*");
    /** Un argomento tra &lt; &gt;, con un livello di annidamento: lo stesso che Translator protegge. */
    private static final Pattern ANGLE_ARGUMENT = Pattern.compile("<(?:[^<>]|<[^<>]*>)*>");

    /** parola italiana -> lingua -> parola tradotta. */
    private static volatile Map<String, Map<String, String>> glossary;

    private HelpSyntax() {
    }

    /** La riga divisa in {sintassi, separatore, descrizione}, o null se non e' una riga di aiuto. */
    static String[] split(String text) {
        Matcher m = LINE.matcher(text);
        return m.matches() ? new String[]{m.group(1), m.group(2), m.group(3)} : null;
    }

    /** La sintassi con i nomi degli argomenti nella lingua indicata; comando e opzioni identici. */
    static String translateSyntax(String syntax, String lang) {
        Map<String, Map<String, String>> words = glossary();
        StringBuilder out = new StringBuilder(syntax.length() + 8);
        int depth = 0;
        int i = 0;
        while (i < syntax.length()) {
            char c = syntax.charAt(i);
            if (c == '<' || c == '[') {
                depth++;
            } else if ((c == '>' || c == ']') && depth > 0) {
                depth--;
            }
            if (depth > 0 && Character.isLetter(c)) {
                String phrase = phraseAt(syntax, i, words);
                if (phrase != null) {
                    String translated = words.get(phrase).get(lang);
                    out.append(translated != null ? translated : phrase);
                    i += phrase.length();
                    continue;
                }
                Matcher m = WORD.matcher(syntax).region(i, syntax.length());
                if (m.lookingAt()) {
                    String word = m.group();
                    Map<String, String> byLang = words.get(word);
                    String translated = byLang != null ? byLang.get(lang) : null;
                    out.append(translated != null ? translated : word);
                    i = m.end();
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Una voce di piu' parole del glossario ("codice a sei cifre") che inizia in {@code at}, o null. */
    private static String phraseAt(String text, int at, Map<String, Map<String, String>> words) {
        String best = null;
        for (String key : words.keySet()) {
            if (key.indexOf(' ') < 0 || !text.startsWith(key, at)) {
                continue;
            }
            int end = at + key.length();
            if (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) {
                continue;
            }
            if (best == null || key.length() > best.length()) {
                best = key;
            }
        }
        return best;
    }

    /** Ogni &lt;...&gt; di un testo qualsiasi con i nomi degli argomenti nella lingua indicata. */
    static String translateAngleArguments(String text, String lang) {
        Matcher m = ANGLE_ARGUMENT.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 8);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start()).append(translateSyntax(m.group(), lang));
            last = m.end();
        }
        return out.append(text, last, text.length()).toString();
    }

    /**
     * Una traduzione gia' in cache con gli argomenti rifatti dal glossario: la sintassi delle righe
     * di aiuto (passata da MyMemory, nelle traduzioni vecchie) e ogni &lt;...&gt; rimasto in
     * italiano negli altri messaggi. Si correggono cosi' da sole, senza consumare quota; rifarlo su
     * un valore gia' corretto non cambia niente. Null se una riga di aiuto in cache ha perso il
     * "::" (da ritradurre).
     */
    static Object refreshCached(Object italianValue, Object cachedTranslated, String lang) {
        if (italianValue instanceof String source && cachedTranslated instanceof String cached) {
            return refreshLine(source, cached, lang);
        }
        if (italianValue instanceof List<?> sourceList && cachedTranslated instanceof List<?> cachedList
                && sourceList.size() == cachedList.size()) {
            List<String> out = new java.util.ArrayList<>(cachedList.size());
            for (int i = 0; i < cachedList.size(); i++) {
                String line = refreshLine(String.valueOf(sourceList.get(i)), String.valueOf(cachedList.get(i)), lang);
                if (line == null) {
                    return null;
                }
                out.add(line);
            }
            return out;
        }
        return cachedTranslated;
    }

    private static String refreshLine(String source, String cached, String lang) {
        String[] src = split(source);
        if (src == null) {
            return translateAngleArguments(cached, lang);
        }
        String[] old = split(cached);
        if (old == null) {
            return null;
        }
        return translateSyntax(src[0], lang) + old[1] + translateAngleArguments(old[2], lang);
    }

    private static Map<String, Map<String, String>> glossary() {
        Map<String, Map<String, String>> g = glossary;
        if (g != null) {
            return g;
        }
        Map<String, Map<String, String>> loaded = new HashMap<>();
        try (InputStream in = HelpSyntax.class.getResourceAsStream("/argument-glossary.yml")) {
            if (in != null) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (Map<?, ?> entry : yaml.getMapList("words")) {
                    Object it = entry.get("it");
                    if (it == null) {
                        continue;
                    }
                    Map<String, String> byLang = new HashMap<>();
                    for (Map.Entry<?, ?> e : entry.entrySet()) {
                        byLang.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                    loaded.put(String.valueOf(it), byLang);
                }
            }
        } catch (Exception e) {
            // senza glossario i nomi degli argomenti restano in italiano: mai un errore di traduzione
        }
        glossary = loaded;
        return loaded;
    }
}
