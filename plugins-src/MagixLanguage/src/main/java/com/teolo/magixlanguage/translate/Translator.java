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

    /** {chiave}, %chiave% (PlaceholderAPI, usato nei menu di MagixMenus), &#RRGGBB, &<colore>,
     *  \n letterale (due caratteri, gestito da Colors.translate), | (separatore, es. nei titoli
     *  "Grande|piccolo" di MagixMenus: senza protezione un servizio di traduzione puo' spostarlo
     *  o toglierlo), &lt;argomento&gt; (es. "/login &lt;password&gt;" nelle righe di uso e di
     *  aiuto: senza protezione MyMemory lo scambia per un tag HTML e mangia lo spazio prima o dopo
     *  - visto succedere davvero, "/login&lt;password&gt;" attaccato; un livello di annidamento,
     *  es. "&lt;info|migrate &lt;sqlite|mariadb&gt;&gt;", e' incluso apposta), e « » (le frecce di
     *  "&lt;&lt; indietro"/"avanti &gt;&gt;" nell'aiuto a pagine, e il separatore "&gt;" di alcuni
     *  prefissi di MagixFactions: senza protezione un servizio di traduzione puo' toglierle). */
    private static final Pattern TOKEN = Pattern.compile(
            "\\{[a-zA-Z0-9_]+}" + "|%[a-zA-Z0-9_]+%" + "|&#[0-9a-fA-F]{6}" + "|&[0-9a-fk-orA-FK-OR]"
                    + "|\\\\n" + "|\\|" + "|<(?:[^<>]|<[^<>]*>)*>" + "|«" + "|»");

    private static final Pattern TRANSLATED_TEXT = Pattern.compile("\"translatedText\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern RESPONSE_STATUS = Pattern.compile("\"responseStatus\"\\s*:\\s*\"?(\\d+)\"?");
    private static final Pattern QUOTA_FINISHED = Pattern.compile("\"quotaFinished\"\\s*:\\s*(true|false)");

    /** Il messaggio di quota finita di MyMemory dice quando riprovare, es. "NEXT AVAILABLE IN 10
     *  HOURS 20 MINUTES 05 SECONDS": letto per non riprovare ne' troppo presto ne' troppo tardi. */
    private static final Pattern NEXT_AVAILABLE = Pattern.compile(
            "(?i)NEXT\\s+AVAILABLE\\s+IN\\s+(?:(\\d+)\\s*HOURS?)?\\s*(?:(\\d+)\\s*MINUTES?)?\\s*(?:(\\d+)\\s*SECONDS?)?");

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
    private boolean blocked;
    private boolean madeRequests;
    private Duration retryHint;

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
        // Uno spazio a inizio o fine testo e' spesso un padding voluto (es. " ‹ indietro " per
        // staccare la scritta dai bordi cliccabili nel piede dell'aiuto a pagine): MyMemory tende
        // a mangiarlo, come qualunque servizio di traduzione che ripulisce i bordi del testo prima
        // di restituirlo. Si toglie prima di mandare il testo e si rimette al suo posto dopo,
        // senza nemmeno passare dalla rete se il risultato sarebbe comunque tutto spazi.
        int start = 0, end = italianText.length();
        while (start < end && Character.isWhitespace(italianText.charAt(start))) start++;
        while (end > start && Character.isWhitespace(italianText.charAt(end - 1))) end--;
        String leading = italianText.substring(0, start);
        String trailing = italianText.substring(end);
        String core = italianText.substring(start, end);

        Protected protectedText = protect(core);
        String raw = call(protectedText.text, targetLang);
        if (raw == null) {
            if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                circuitOpen = true;
                blocked = true;
                log.warning("MagixLanguage: " + MAX_CONSECUTIVE_FAILURES + " traduzioni di fila fallite "
                        + "(probabile limite del servizio raggiunto): interrotta la traduzione automatica per "
                        + "il resto di questo giro, le chiavi restanti restano in italiano per ora.");
            }
            return null;
        }
        consecutiveFailures = 0;
        String restored = restore(raw, protectedText.tokens);
        if (LEFTOVER_TOKEN.matcher(restored).find()) {
            // Il servizio ha alterato un segnaposto (es. tolto una lettera) al punto che non lo
            // si e' piu' riconosciuto per rimetterlo a posto: meglio niente traduzione che un
            // messaggio con un pezzo rotto in mezzo (visto succedere davvero: "&7" sparito e
            // rimasto un residuo tipo "[2]" al suo posto). Conta come fallimento, si riprova dopo.
            log.warning("MagixLanguage: traduzione verso " + targetLang + " scartata: un segnaposto non e' "
                    + "tornato al suo posto (testo: " + restored + ").");
            return null;
        }
        for (String token : protectedText.tokens()) {
            if (!restored.contains(token)) {
                // Caso peggiore del precedente: il wrapper qx/xq e' sparito INSIEME al colore o al
                // placeholder che proteggeva, invece di lasciarne un residuo riconoscibile (visto
                // succedere davvero: "qx0xq" e "qx1xq" di una riga fatta solo di due codici colore
                // ridotti al numero nudo "0 1", che LEFTOVER_TOKEN non intercetta perche' non
                // assomiglia piu' a un segnaposto). Si verifica che ogni pezzo protetto sia
                // davvero tornato, non solo che non ne resti un residuo visibile.
                log.warning("MagixLanguage: traduzione verso " + targetLang + " scartata: un colore o un "
                        + "placeholder e' sparito invece di tornare al suo posto (mancante: " + token
                        + ", testo: " + restored + ").");
                return null;
            }
        }
        return leading + restored + trailing;
    }

    /** Se false, {@link #translate} non prova nemmeno piu' la rete: vedi {@link #MAX_CONSECUTIVE_FAILURES}. */
    public boolean isAvailable() {
        return !circuitOpen;
    }

    /**
     * Apre il circuito senza nemmeno provare una chiamata: usato quando non e' il momento di
     * chiamare MyMemory (pausa dopo un blocco, o intervallo fra un giro e l'altro non ancora
     * passato: vedi {@link TranslationPacing}). Le chiavi gia' in cache continuano comunque a
     * funzionare: solo le chiamate di rete vere e proprie si fermano.
     */
    public void forceUnavailable() {
        circuitOpen = true;
    }

    /** Se in questo giro MyMemory ha rifiutato abbastanza richieste di fila da far smettere. */
    public boolean wasBlocked() {
        return blocked;
    }

    /** Se in questo giro e' partita almeno una richiesta vera verso MyMemory. */
    public boolean madeRequests() {
        return madeRequests;
    }

    /** Fra quanto MyMemory ha detto di riprovare, se l'ha detto (vedi {@link #NEXT_AVAILABLE}). */
    public Duration retryHint() {
        return retryHint;
    }

    private void noteRetryHint(String text) {
        if (text == null) {
            return;
        }
        Matcher m = NEXT_AVAILABLE.matcher(text);
        if (!m.find() || (m.group(1) == null && m.group(2) == null && m.group(3) == null)) {
            return;
        }
        Duration d = Duration.ofHours(m.group(1) != null ? Long.parseLong(m.group(1)) : 0)
                .plusMinutes(m.group(2) != null ? Long.parseLong(m.group(2)) : 0)
                .plusSeconds(m.group(3) != null ? Long.parseLong(m.group(3)) : 0);
        retryHint = d;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "..." : flat;
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
            madeRequests = true;
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                noteRetryHint(response.body());
                response.headers().firstValue("Retry-After").ifPresent(v -> {
                    if (retryHint == null && v.trim().matches("\\d+")) {
                        retryHint = Duration.ofSeconds(Long.parseLong(v.trim()));
                    }
                });
                // Il corpo della risposta dice il perche' (quota finita, e fra quanto torna): senza
                // stamparlo si poteva solo tirare a indovinare.
                log.warning("MagixLanguage: traduzione verso " + targetLang + " rifiutata (risposta HTTP "
                        + response.statusCode() + ": " + snippet(response.body()) + ").");
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
            noteRetryHint(json);
            log.warning("MagixLanguage: MyMemory ha rifiutato la traduzione verso " + targetLang
                    + " (responseStatus " + status.group(1) + ": " + snippet(json) + ").");
            return null;
        }
        Matcher quota = QUOTA_FINISHED.matcher(json);
        if (quota.find() && Boolean.parseBoolean(quota.group(1))) {
            noteRetryHint(json);
            log.warning("MagixLanguage: quota giornaliera di MyMemory esaurita per " + targetLang
                    + " (resta il testo italiano nel frattempo).");
            return null;
        }
        Matcher text = TRANSLATED_TEXT.matcher(json);
        if (!text.find()) {
            log.warning("MagixLanguage: risposta di traduzione verso " + targetLang + " non capita.");
            return null;
        }
        String translated = unescape(text.group(1));
        if (translated.toUpperCase(java.util.Locale.ROOT).contains("MYMEMORY WARNING")) {
            noteRetryHint(translated);
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

    /**
     * Segnaposto per numero N durante la chiamata al servizio: una parola alfanumerica senza
     * parentesi ne' altra punteggiatura, cosi' un traduttore automatico non ha niente da
     * "correggere" (visto succedere davvero con {@code [[N]]}: un servizio l'ha scambiato per un
     * riferimento a nota a pie' di pagina e ha tolto una coppia di parentesi, lasciando {@code
     * [N]} visibile nel testo finale invece del colore o del placeholder che doveva sostituire).
     */
    private static final String TOKEN_PREFIX = "qx";
    private static final String TOKEN_SUFFIX = "xq";

    /** Un segnaposto rimasto non riconosciuto dopo restore(): la traduzione non e' affidabile. */
    private static final Pattern LEFTOVER_TOKEN = Pattern.compile(
            "(?i)" + TOKEN_PREFIX + "[\\s\\-_⁣]*\\d+[\\s\\-_⁣]*" + TOKEN_SUFFIX);

    /**
     * U+2063 INVISIBLE SEPARATOR: nessun rendering, nessun significato, esiste apposta per
     * segnare un confine senza comparire. Inserito da protect() fra due segnaposto ADIACENTI nel
     * testo originale (es. "&e{power}&7", comunissimo nei messaggi di MagixFactions), che senza
     * questo diventerebbero un unico blocco "qx0xqqx1xq" indistinguibile da una parola sola: visto
     * succedere davvero che MyMemory ne storpi uno dei due (una lettera persa) quando sono incollati
     * cosi', mentre la stessa coppia separata da un carattere qualunque sopravvive. Tolto sempre da
     * restore(), che sia sopravvissuto, spostato o gia' sparito: non deve mai comparire nel
     * messaggio finale, e non ha bisogno di sopravvivere alla traduzione per aver gia' fatto il suo
     * lavoro (rompere il blocco al momento dell'invio).
     */
    private static final String GLUE_BREAKER = "⁣";

    /**
     * I pezzi (colori, placeholder...) che {@link #protect} avrebbe isolato in {@code text}: usato
     * da {@code TranslationSync} per verificare che una traduzione gia' in cache li contenga
     * ancora tutti, senza dover rifare la chiamata di traduzione per saperlo.
     */
    public static List<String> requiredTokens(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            tokens.add(m.group());
        }
        return tokens;
    }

    private record Protected(String text, List<String> tokens) {}

    private static Protected protect(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(text, last, m.start());
            if (m.start() == last && !tokens.isEmpty()) {
                // Due segnaposto senza NIENTE fra loro nel testo originale (es. "&e{power}&7"):
                // vedi GLUE_BREAKER per il perche'.
                sb.append(GLUE_BREAKER);
            }
            sb.append(TOKEN_PREFIX).append(tokens.size()).append(TOKEN_SUFFIX);
            tokens.add(m.group());
            last = m.end();
        }
        sb.append(text, last, text.length());
        return new Protected(sb.toString(), tokens);
    }

    private static String restore(String translated, List<String> tokens) {
        String out = translated;
        for (int i = 0; i < tokens.size(); i++) {
            // Il servizio a volte cambia il maiuscolo/minuscolo o gli spazi intorno al segnaposto
            // (es. lo maiuscolizza a inizio frase): si cerca senza badarci ne' all'uno ne' agli altri.
            out = out.replaceAll("(?i)" + TOKEN_PREFIX + "[\\s\\-_⁣]*" + i + "[\\s\\-_⁣]*" + TOKEN_SUFFIX,
                    Matcher.quoteReplacement(tokens.get(i)));
        }
        // GLUE_BREAKER ha gia' fatto il suo lavoro (separare i segnaposto all'invio): non deve
        // comparire nel messaggio finale, indipendentemente da dove il servizio l'abbia lasciato.
        return out.replace(GLUE_BREAKER, "");
    }
}
