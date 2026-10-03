package com.teolo.magixlanguage.translate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Controls on a translation and the translation of one value (a line or a list of lines),
 * shared by {@link TranslationSync} (Paper) and {@link ProxyTranslationSync} (Velocity): no
 * Bukkit and no Velocity types here, so the same class loads on both.
 */
final class TranslationChecks {

    private TranslationChecks() {
    }

    /**
     * Residui di un vecchio segnaposto non ripristinato (visto succedere davvero: il servizio di
     * traduzione ha alterato {@code [[N]]} in {@code [N]}, lasciando quel residuo al posto di un
     * colore o di un placeholder). Una cache con un valore cosi' non si riusa: si ritraduce.
     */
    private static final Pattern SUSPECT_LEFTOVER = Pattern.compile("(?i)qx\\s*\\d+\\s*xq|\\[\\d+]");

    /**
     * Uno spazio mangiato intorno a un argomento tra &lt; &gt; (visto succedere davvero: MyMemory
     * lo scambiava per un tag HTML prima che {@link Translator} lo proteggesse, lasciando
     * "/login&lt;password&gt;" invece di "/login &lt;password&gt;"). In un testo corretto una
     * lettera o una cifra non tocca mai direttamente "&lt;" o "&gt;": una cache con un valore cosi'
     * viene dalle traduzioni fatte prima di quella protezione e va rifatta.
     */
    private static final Pattern GLUED_BRACKET = Pattern.compile("[\\p{L}\\p{N}]<|>[\\p{L}\\p{N}]");

    /** True se una stringa ha uno spazio a inizio/fine e l'altra no: un padding voluto (es.
     *  " « indietro " per staccare la scritta dai bordi cliccabili) che un servizio di traduzione
     *  ha mangiato non lascia mai un valore identico su questo fronte. */
    private static boolean edgeWhitespaceMismatch(String source, String translated) {
        boolean sourceLeading = !source.isEmpty() && Character.isWhitespace(source.charAt(0));
        boolean sourceTrailing = !source.isEmpty() && Character.isWhitespace(source.charAt(source.length() - 1));
        boolean translatedLeading = !translated.isEmpty() && Character.isWhitespace(translated.charAt(0));
        boolean translatedTrailing = !translated.isEmpty() && Character.isWhitespace(translated.charAt(translated.length() - 1));
        return sourceLeading != translatedLeading || sourceTrailing != translatedTrailing;
    }

    private static boolean lineCorrupted(String source, String translated, String lang) {
        String[] sourceHelp = source != null ? HelpSyntax.split(source) : null;
        if (sourceHelp != null) {
            // Riga di aiuto: la sintassi la rifa ogni volta il glossario (e "<fazione>" diventa
            // "<faction>", che il controllo dei pezzi protetti scambierebbe per un placeholder
            // perso): conta solo la descrizione.
            String[] translatedHelp = HelpSyntax.split(translated);
            if (translatedHelp == null) {
                return true;
            }
            source = sourceHelp[2];
            translated = translatedHelp[2];
        }
        return SUSPECT_LEFTOVER.matcher(translated).find() || GLUED_BRACKET.matcher(translated).find()
                || (source != null && edgeWhitespaceMismatch(source, translated))
                || (source != null && missingProtectedToken(source, translated, lang));
    }

    /**
     * Un colore o un placeholder che il testo italiano richiede ma che non compare piu' nel
     * testo tradotto in cache (visto succedere davvero: due codici colore protetti come "qx0xq"/
     * "qx1xq" ridotti dal servizio di traduzione al solo numero nudo "0 1", senza lasciare il
     * residuo "qxNxq" che {@link #SUSPECT_LEFTOVER} intercetterebbe). Una cache cosi' va rifatta.
     */
    private static boolean missingProtectedToken(String source, String translated, String lang) {
        for (String token : Translator.requiredTokens(source)) {
            // un <argomento> torna tradotto col glossario (vedi Translator), non identico
            String expected = HelpSyntax.translateAngleArguments(token, lang);
            if (!translated.contains(expected)) {
                return true;
            }
        }
        return false;
    }

    static boolean looksCorrupted(Object italianValue, Object cachedTranslated, String lang) {
        if (cachedTranslated instanceof String s) {
            String source = italianValue instanceof String is ? is : null;
            return lineCorrupted(source, s, lang);
        }
        if (cachedTranslated instanceof List<?> list) {
            List<?> sourceList = italianValue instanceof List<?> sl ? sl : null;
            for (int i = 0; i < list.size(); i++) {
                Object line = list.get(i);
                if (line == null) continue;
                String source = sourceList != null && i < sourceList.size() && sourceList.get(i) != null
                        ? String.valueOf(sourceList.get(i)) : null;
                if (lineCorrupted(source, String.valueOf(line), lang)) {
                    return true;
                }
            }
        }
        return false;
    }

    static Object translateValue(Translator translator, Object italianValue, String lang) {
        if (italianValue instanceof String s) {
            return translator.translate(s, lang);
        }
        if (italianValue instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object line : list) {
                String translated = translator.translate(String.valueOf(line), lang);
                if (translated == null) {
                    return null; // una riga sola non tradotta: si riprova tutta la lista al prossimo giro
                }
                out.add(translated);
            }
            return out;
        }
        return null;
    }
}
