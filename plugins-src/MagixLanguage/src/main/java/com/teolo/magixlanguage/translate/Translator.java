package com.teolo.magixlanguage.translate;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traduce un testo dall'italiano a un'altra lingua tramite l'API pubblica e gratuita di
 * MyMemory (nessuna chiave richiesta). A differenza dell'endpoint interno di Google Translate
 * (pensato per la pagina web, non per chiamate automatiche — blocca con "automated queries"
 * anche una singola richiesta da un IP di un servizio cloud), MyMemory e' una vera API pensata
 * per questo uso, con un limite di 5000 parole/giorno per IP anonimo (10000 indicando un
 * contatto, vedi {@code contactEmail}). E' comunque un servizio non ufficiale e non garantito:
 * ogni chiamata puo' fallire (rete, quota esaurita, risposta cambiata), e in quel caso
 * {@link #translate} restituisce {@code null} — mai un'eccezione, mai un testo a meta'. Chi
 * chiama ricade sempre sul testo italiano quando questo succede (vedi TranslationSync).
 *
 * <h2>Placeholder e codici colore</h2>
 * Un traduttore automatico non sa che {@code {player}} o {@code &#C046E8} non sono parole:
 * prima di mandare il testo li si sostituisce con segnaposto numerati ({@code [[0]]},
 * {@code [[1]]}...), che il servizio lascia stare, e li si rimette al loro posto dopo. Senza
 * questo, un placeholder tradotto o spostato romperebbe il messaggio per chi lo riceve.
 */
public final class Translator {

    private static final String ENDPOINT = "https://api.mymemory.translated.net/get";

    /** {chiave}, &#RRGGBB, &<colore>, \n letterale (due caratteri, gestito da Colors.translate). */
    private static final Pattern TOKEN = Pattern.compile(
            "\\{[a-zA-Z0-9_]+}" + "|&#[0-9a-fA-F]{6}" + "|&[0-9a-fk-orA-FK-OR]" + "|\\\\n");

    private static final Pattern TRANSLATED_TEXT = Pattern.compile("\"translatedText\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern RESPONSE_STATUS = Pattern.compile("\"responseStatus\"\\s*:\\s*\"?(\\d+)\"?");
    private static final Pattern QUOTA_FINISHED = Pattern.compile("\"quotaFinished\"\\s*:\\s*(true|false)");

    /** Dopo tante chiamate fallite DI FILA (429, quota, rete) si smette di provare per il resto
     *  di questa sincronizzazione, invece di martellare il servizio senza pause fra un fallimento
     *  e l'altro (il delay configurato scatta solo intorno a un tentativo vero): si riprova tutto
     *  da capo alla sincronizzazione successiva, quando l'istanza sara' un'altra. */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final HttpClient client;
    private final int timeoutMillis;
    private final Logger log;
    private final String contactEmail;
    private int consecutiveFailures;
    private boolean circuitOpen;

    public Translator(int timeoutMillis, Logger log) {
        this(timeoutMillis, log, null);
    }

    /**
     * @param contactEmail indirizzo da mandare a MyMemory nel parametro {@code de} per avere
     *                      una quota giornaliera piu' alta (10000 parole invece di 5000). Puo'
     *                      essere {@code null} o vuoto per restare anonimi.
     */
    public Translator(int timeoutMillis, Logger log, String contactEmail) {
        this.timeoutMillis = Math.max(500, timeoutMillis);
        this.log = log;
        this.contactEmail = contactEmail;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMillis))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Il testo tradotto verso {@code targetLang} (es. "en", "es", "de"), o {@code null} se il
     * servizio non ha risposto in tempo, ha risposto in un modo che non sappiamo leggere, o si
     * e' smesso di provare per questa sincronizzazione (vedi {@link #isAvailable()}).
     */
    public String translate(String italianText, String targetLang) {
        if (italianText == null || italianText.isBlank()) {
            return italianText;
        }
        if (circuitOpen) {
            return null;
        }
        Protected protectedText = protect(italianText);
        String raw = call(protectedText.text, targetLang);
        if (raw == null) {
            if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                circuitOpen = true;
                log.warning("MagixLanguage: " + MAX_CONSECUTIVE_FAILURES + " traduzioni di fila fallite "
                        + "(probabile limite del servizio raggiunto): interrotta la traduzione automatica per "
                        + "il resto di questa sincronizzazione, le chiavi restanti restano in italiano e si "
                        + "riprova dal prossimo /language sync o riavvio.");
            }
            return null;
        }
        consecutiveFailures = 0;
        return restore(raw, protectedText.tokens);
    }

    /** Se false, {@link #translate} non prova nemmeno piu' la rete: vedi {@link #MAX_CONSECUTIVE_FAILURES}. */
    public boolean isAvailable() {
        return !circuitOpen;
    }

    private String call(String text, String targetLang) {
        try {
            // java.net.URI e' rigido con l'RFC 3986: "|" (in "it|en") non e' un carattere
            // valido in una query e va percent-encoded, anche se un client piu' permissivo
            // (curl, i browser) lo accetterebbe cosi' com'e'.
            StringBuilder url = new StringBuilder(ENDPOINT)
                    .append("?q=").append(URLEncoder.encode(text, StandardCharsets.UTF_8))
                    .append("&langpair=").append(URLEncoder.encode("it|" + targetLang, StandardCharsets.UTF_8));
            if (contactEmail != null && !contactEmail.isBlank()) {
                url.append("&de=").append(URLEncoder.encode(contactEmail, StandardCharsets.UTF_8));
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("User-Agent", "Mozilla/5.0 (MagixLanguage; magicadventure.it)")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warning("MagixLanguage: traduzione verso " + targetLang + " rifiutata (risposta HTTP "
                        + response.statusCode() + ").");
                return null;
            }
            return parse(response.body(), targetLang);
        } catch (Exception e) {
            log.warning("MagixLanguage: traduzione verso " + targetLang + " fallita (" + e + ").");
            return null;
        }
    }

    private String parse(String json, String targetLang) {
        Matcher status = RESPONSE_STATUS.matcher(json);
        if (status.find() && !"200".equals(status.group(1))) {
            log.warning("MagixLanguage: MyMemory ha rifiutato la traduzione verso " + targetLang
                    + " (responseStatus " + status.group(1) + ").");
            return null;
        }
        Matcher quota = QUOTA_FINISHED.matcher(json);
        if (quota.find() && Boolean.parseBoolean(quota.group(1))) {
            log.warning("MagixLanguage: quota giornaliera di MyMemory esaurita per " + targetLang
                    + ": si riprova al prossimo sync (resta il testo italiano nel frattempo).");
            return null;
        }
        Matcher text = TRANSLATED_TEXT.matcher(json);
        if (!text.find()) {
            log.warning("MagixLanguage: risposta di traduzione verso " + targetLang + " non capita.");
            return null;
        }
        String translated = unescape(text.group(1));
        if (translated.toUpperCase(java.util.Locale.ROOT).contains("MYMEMORY WARNING")) {
            log.warning("MagixLanguage: MyMemory ha risposto con un avviso invece di una traduzione verso "
                    + targetLang + ": " + translated);
            return null;
        }
        return translated;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> out.append(next);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------- placeholder e colori

    private record Protected(String text, List<String> tokens) {}

    private static Protected protect(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            sb.append("[[").append(tokens.size()).append("]]");
            tokens.add(m.group());
            last = m.end();
        }
        sb.append(text, last, text.length());
        return new Protected(sb.toString(), tokens);
    }

    private static String restore(String translated, List<String> tokens) {
        String out = translated;
        for (int i = 0; i < tokens.size(); i++) {
            // Il servizio a volte cambia gli spazi intorno al segnaposto: si cerca senza badarci.
            out = out.replaceAll("\\[\\[\\s*" + i + "\\s*]]", Matcher.quoteReplacement(tokens.get(i)));
        }
        return out;
    }
}
