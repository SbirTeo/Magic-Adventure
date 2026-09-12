package com.teolo.magixauth.premium;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chiede a Mojang se un nome esiste come account premium, e qual e' la sua skin.
 *
 * L'annotazione dell'account serve a UNA cosa sola: sapere. Non decide niente, non sblocca
 * niente, non salta nessuna password. E' importante essere chiari sul perche', perche' la
 * tentazione di usarlo per far entrare la gente senza password e' forte ed e' sbagliata:
 * quella risposta dice che il NOME appartiene a un account premium, non che chi si sta
 * collegando sia il suo proprietario. Su un server in offline mode chiunque puo' scrivere
 * qualunque nome.
 *
 * La skin invece serve subito. In offline mode il gioco non la chiede piu' a nessuno, e
 * senza questa classe tutti apparirebbero con quella predefinita. Peggio: chi entra da un
 * launcher non ufficiale non vede la skin di serie ma quella che il SUO launcher tiene
 * registrata per quel nickname — cioe' quella di uno sconosciuto che si e' preso quel nome
 * su un servizio di skin qualunque. L'unico modo di non vedersi addosso la faccia di un
 * altro e' che il server mandi sempre quella vera.
 */
public final class MojangLookup {

    private static final String ENDPOINT = "https://api.mojang.com/users/profiles/minecraft/";

    /** Da qui si prendono le skin: e' il servizio che restituisce il profilo completo. */
    private static final String PROFILO = "https://sessionserver.mojang.com/session/minecraft/profile/";

    /** L'UUID arriva senza trattini: vanno rimessi per farne un UUID vero. */
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");

    /** La proprieta' "textures" del profilo, col suo valore e la firma di Mojang. */
    private static final Pattern TEXTURES = Pattern.compile(
            "\\{\\s*\"name\"\\s*:\\s*\"textures\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]+)\""
            + "(?:\\s*,\\s*\"signature\"\\s*:\\s*\"([^\"]+)\")?");

    /** Dentro al blob base64 delle texture c'e' scritto di CHI sono. */
    private static final Pattern PROFILE_ID = Pattern.compile("\"profileId\"[^\"]*\"([0-9a-fA-F]{32})\"");

    private final HttpClient client;
    private final int timeoutMillis;
    private final long cacheMillis;
    private final Logger log;

    /**
     * L'ultima skin BUONA vista per ogni nome, tenuta su disco e ricaricata all'avvio.
     *
     * La cache in memoria ({@link #skinViste}) sparisce a ogni riavvio: subito dopo il boot e'
     * vuota, quindi il primo giocatore che entra dipende interamente da una risposta di Mojang
     * entro il timeout — e a freddo (JVM che carica JSSE, TLS, DNS, sei core occupati dal boot)
     * quella risposta spesso non arriva in tempo. Risultato: entrava con la faccia di uno
     * sconosciuto. Salvando su disco l'ultima skin vera di ognuno, all'avvio la ricarichiamo in
     * cache: chi rientra la vede subito, senza nemmeno chiamare Mojang. E se una chiamata vera
     * fallisce piu' avanti, questa resta la rete di sicurezza al posto del profilo sbagliato.
     */
    private final java.nio.file.Path storeFile;
    private final Map<String, String[]> lastGood = new ConcurrentHashMap<>();
    private final Object storeLock = new Object();

    /**
     * Le skin gia' chieste, per nome, con la loro scadenza.
     *
     * Senza cache, ogni ingresso costerebbe un viaggio fino ai server di Mojang mentre il
     * giocatore aspetta davanti a uno schermo fermo. Con una cache ETERNA, pero', si paga
     * molto piu' caro, ed e' successo davvero: una singola stretta di mano TLS rifiutata da
     * `api.mojang.com` — capita, sta dietro a un CDN che ogni tanto chiude la connessione —
     * veniva registrata come "questo nome non ha skin" e restava li' fino al riavvio del
     * server. Mezzo secondo di rete storta, e quel giocatore restava senza la sua skin per
     * giorni. Da qui le due regole di sotto: le voci scadono, e i FALLIMENTI non si
     * scrivono affatto.
     */
    private final Map<String, Entry> skinViste = new ConcurrentHashMap<>();

    /** Una skin in cache, con il momento in cui va richiesta di nuovo. */
    private record Entry(String[] skin, long scadenza) {
        boolean valida() {
            return System.currentTimeMillis() < scadenza;
        }
    }

    /**
     * Il risultato di una ricerca: la skin, e se la ricerca e' proprio FALLITA.
     *
     * La differenza conta. "Questo nome non ha un account premium" e' una risposta, si puo'
     * tenere da parte. "Mojang non ha risposto" non e' una risposta: non va messa in cache
     * e merita una riga nel log, altrimenti un problema di rete resta invisibile per sempre
     * — che e' esattamente com'era prima.
     */
    public record Found(String[] skin, boolean failed, String reason) {
        static Found riuscita(String[] skin) {
            return new Found(skin, false, "");
        }
        static Found failed(String reason) {
            return new Found(null, true, reason);
        }
    }

    /** Com'e' andata una singola chiamata. */
    private enum Outcome { OK, ASSENTE, FALLITA }

    private record Reply(Outcome outcome, String body, String reason) {}

    public MojangLookup(int timeoutMillis, int minutiCache, Logger log) {
        this(timeoutMillis, minutiCache, log, null);
    }

    public MojangLookup(int timeoutMillis, int minutiCache, Logger log, java.nio.file.Path storeFile) {
        this.timeoutMillis = timeoutMillis;
        this.cacheMillis = Math.max(1, minutiCache) * 60_000L;
        this.log = log;
        this.storeFile = storeFile;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        loadFromDisk();
    }

    /**
     * La skin da mettere addosso a chi sta entrando.
     *
     * Quando l'account ha gia' un UUID premium — tutti quelli che erano qui ai tempi
     * dell'online mode ce l'hanno — si va DRITTI al sessionserver con quello, saltando la
     * traduzione nome → UUID. Non e' solo un viaggio risparmiato: `api.mojang.com` e' il
     * pezzo che ogni tanto rifiuta la connessione, e toglierlo dal percorso di ingresso
     * toglie di mezzo la causa piu' probabile di una skin mancante.
     *
     * Va chiesta la versione FIRMATA (`unsigned=false`): il client accetta una skin non sua
     * solo se porta la firma di Mojang, altrimenti la ignora e resta con quella di serie.
     *
     * @param uuidAccount l'UUID salvato per questo account, o null se non ne ha uno
     * @param nome        il nome con cui si sta collegando
     */
    public Found skinOf(UUID accountUuid, String name) {
        if (name == null) {
            return Found.riuscita(null);
        }
        String key = name.toLowerCase(Locale.ROOT);
        Entry inCache = skinViste.get(key);
        if (inCache != null && inCache.valida()) {
            return Found.riuscita(inCache.skin());
        }

        // La scorciatoia: se l'UUID dell'account e' un UUID Mojang (versione 4, mentre
        // quelli ricavati dal nome in offline mode sono di versione 3) il profilo si puo'
        // chiedere subito, senza passare dal nome.
        if (accountUuid != null && accountUuid.version() == 4) {
            Reply prof = invoke(PROFILO + withoutDashes(accountUuid) + "?unsigned=false");
            if (prof.outcome() == Outcome.OK) {
                return remember(key, texture(prof.body(), accountUuid));
            }
            if (prof.outcome() == Outcome.FALLITA) {
                return fallbackOrFailed(key, prof.reason());
            }
            // ASSENTE: quell'UUID non esiste piu'. Si riprova per nome.
        }

        Reply cercato = invoke(ENDPOINT + name);
        if (cercato.outcome() == Outcome.FALLITA) {
            return fallbackOrFailed(key, cercato.reason());
        }
        if (cercato.outcome() == Outcome.ASSENTE) {
            // Nome libero: nessun account premium si chiama cosi'. E' una risposta vera, e
            // come tale si puo' tenere da parte.
            return remember(key, null);
        }
        UUID premium = uuidFrom(cercato.body());
        if (premium == null) {
            return remember(key, null);
        }
        Reply prof = invoke(PROFILO + withoutDashes(premium) + "?unsigned=false");
        if (prof.outcome() == Outcome.FALLITA) {
            return fallbackOrFailed(key, prof.reason());
        }
        return remember(key, prof.outcome() == Outcome.OK ? texture(prof.body(), premium) : null);
    }

    /**
     * Quando Mojang non risponde: se di questo nome abbiamo gia' salvato una skin buona (da un
     * ingresso precedente, ricaricata dal disco all'avvio), la si rimette al posto della faccia
     * di uno sconosciuto. Il fallimento resta nel log, ma il giocatore vede comunque SE STESSO.
     * Se invece non l'abbiamo mai vista, non c'e' niente da mettere: fallimento vero.
     */
    private Found fallbackOrFailed(String key, String reason) {
        String[] saved = lastGood.get(key);
        if (saved != null) {
            log.warning("MagixAuth: Mojang non ha risposto per " + key + " (" + reason
                    + "). Uso l'ultima skin salvata di questo nome.");
            return Found.riuscita(saved);
        }
        return Found.failed(reason);
    }

    /** L'UUID Mojang di un nome, se il nome e' un account premium. */
    public UUID lookup(String name) {
        if (name == null || !name.matches("[A-Za-z0-9_]{3,16}")) {
            return null;
        }
        Reply r = invoke(ENDPOINT + name);
        return r.outcome() == Outcome.OK ? uuidFrom(r.body()) : null;
    }

    /** La skin di un UUID, chiesta adesso, senza passare dalla cache. */
    public String[] skin(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Reply r = invoke(PROFILO + withoutDashes(uuid) + "?unsigned=false");
        return r.outcome() == Outcome.OK ? texture(r.body(), uuid) : null;
    }

    private Found remember(String key, String[] skin) {
        skinViste.put(key, new Entry(skin, System.currentTimeMillis() + cacheMillis));
        // Solo le skin VERE si tengono da parte su disco: un "nome libero" (skin null) non va
        // salvato, e soprattutto non deve mai sovrascrivere una skin buona vista in precedenza.
        if (skin != null && skin.length >= 1 && skin[0] != null) {
            String[] prima = lastGood.put(key, skin);
            if (prima == null || !java.util.Arrays.equals(prima, skin)) {
                saveToDisk();
            }
        }
        return Found.riuscita(skin);
    }

    /**
     * Carica dal disco l'ultima skin buona di ogni nome e la rimette in cache come valida, cosi'
     * chi rientra subito dopo un riavvio la vede senza dover aspettare (ne' rischiare) una
     * risposta di Mojang a freddo. Un file assente o una riga malformata non sono un errore: si
     * riparte semplicemente senza quella voce.
     */
    private void loadFromDisk() {
        if (storeFile == null || !java.nio.file.Files.isReadable(storeFile)) {
            return;
        }
        int loaded = 0;
        long scadenza = System.currentTimeMillis() + cacheMillis;
        try {
            for (String line : java.nio.file.Files.readAllLines(storeFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                // formato: nome<TAB>value<TAB>signature  (signature puo' mancare)
                String[] parts = line.split("\t", -1);
                if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) continue;
                String key = parts[0].toLowerCase(Locale.ROOT);
                String[] skin = new String[]{parts[1], parts.length >= 3 && !parts[2].isEmpty() ? parts[2] : null};
                lastGood.put(key, skin);
                skinViste.put(key, new Entry(skin, scadenza));
                loaded++;
            }
            if (loaded > 0) {
                log.info("MagixAuth: " + loaded + " skin ricaricate dal disco (pronte per il primo ingresso).");
            }
        } catch (Exception e) {
            log.warning("MagixAuth: impossibile leggere le skin salvate (" + e + "). Si riparte senza.");
        }
    }

    /** Riscrive su disco tutte le skin buone conosciute (poche righe, scritte di rado: solo quando
     *  una skin nuova o cambiata viene vista davvero). Scrittura atomica: prima un file di lato,
     *  poi la sostituzione, per non lasciare mai il file a meta' se il server viene fermato. */
    private void saveToDisk() {
        if (storeFile == null) {
            return;
        }
        synchronized (storeLock) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String[]> e : lastGood.entrySet()) {
                String[] skin = e.getValue();
                if (skin == null || skin.length < 1 || skin[0] == null) continue;
                sb.append(e.getKey()).append('\t').append(skin[0]).append('\t')
                        .append(skin.length >= 2 && skin[1] != null ? skin[1] : "").append('\n');
            }
            try {
                java.nio.file.Path dir = storeFile.getParent();
                if (dir != null) java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path tmp = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
                java.nio.file.Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                try {
                    java.nio.file.Files.move(tmp, storeFile,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException noAtomic) {
                    java.nio.file.Files.move(tmp, storeFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                log.warning("MagixAuth: impossibile salvare le skin su disco (" + e + ").");
            }
        }
    }

    /**
     * Una chiamata a vuoto all'avvio, per scaldare il collegamento.
     *
     * La PRIMA chiamata HTTPS di una JVM paga tutto in una volta: caricamento di JSSE, lettura
     * dei certificati, DNS, stretta di mano TLS. Su un server appena avviato — sei processori
     * occupati a caricare mondo e plugin — quel primo giro puo' superare i tre secondi di
     * timeout, e chi entra per primo si ritrova senza la sua skin. E' esattamente quello che si
     * e' visto nel log del 2026-08-30: MagixAuth e MagixEntities falliti a due secondi l'uno
     * dall'altro, subito dopo l'avvio, e tutto a posto ai collegamenti successivi.
     * Qui il conto lo paga il server mentre nessuno sta ancora entrando.
     */
    public void warmUp() {
        invoke(PROFILO + "00000000000000000000000000000000");
    }

    /**
     * Una chiamata a Mojang, con la differenza fra "ha detto di no" e "non ha risposto".
     *
     * Non lancia mai: chi la usa non deve poter impedire a nessuno di entrare per colpa di
     * un servizio esterno finito sul percorso di ingresso al server.
     */
    private Reply invoke(String url) {
        Reply primo = attempt(url);
        if (primo.outcome() != Outcome.FALLITA) {
            return primo;
        }
        // Un secondo tentativo subito: quasi tutti i guasti visti qui sono del primo colpo
        // (stretta di mano TLS chiusa dal CDN, collegamento ancora freddo). Riprovare costa
        // molto meno che lasciare entrare qualcuno con la faccia sbagliata.
        Reply secondo = attempt(url);
        return secondo.outcome() == Outcome.FALLITA
                ? new Reply(Outcome.FALLITA, "", secondo.reason() + " (al secondo tentativo)")
                : secondo;
    }

    private Reply attempt(String url) {
        try {
            HttpRequest richiesta = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("User-Agent", "MagixAuth (magicadventure.it)")
                    .GET()
                    .build();
            HttpResponse<String> risposta = client.send(richiesta, HttpResponse.BodyHandlers.ofString());
            int code = risposta.statusCode();
            if (code == 200) {
                return new Reply(Outcome.OK, risposta.body(), "");
            }
            // 204 e 404 sono una risposta: quel nome non esiste. Tutto il resto (429 troppe
            // richieste, 5xx, ...) e' un guaio momentaneo di Mojang, non un dato sul nome.
            return new Reply(code == 204 || code == 404 ? Outcome.ASSENTE : Outcome.FALLITA, "",
                    "risposta " + code);
        } catch (Exception e) {
            return new Reply(Outcome.FALLITA, "", e.toString());
        }
    }

    private static String withoutDashes(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    private static UUID uuidFrom(String body) {
        Matcher m = ID.matcher(body);
        if (!m.find()) {
            return null;
        }
        String bare = m.group(1);
        return UUID.fromString(
                bare.substring(0, 8) + "-" + bare.substring(8, 12) + "-" +
                bare.substring(12, 16) + "-" + bare.substring(16, 20) + "-" + bare.substring(20));
    }

    /**
     * La texture del profilo, ma solo se e' davvero DI QUEL PROFILO.
     *
     * Dentro al blob base64 Mojang scrive `profileId`: e' l'unico modo di accorgersi che la
     * risposta arrivata non e' quella chiesta. Non dovrebbe mai succedere — ma il servizio
     * sta dietro a un CDN, e attaccare addosso a qualcuno la faccia di un estraneo e' un
     * guasto che, senza questo controllo, non lascia nessuna traccia da nessuna parte:
     * quello che si vede e' un giocatore che si lamenta di avere la skin di un altro, e nel
     * log non c'e' niente. Meglio nessuna skin che la skin sbagliata.
     */
    private String[] texture(String body, UUID expected) {
        Matcher m = TEXTURES.matcher(body);
        if (!m.find()) {
            return null;
        }
        String value = m.group(1);
        if (expected != null && !diChi(value).isEmpty()
                && !diChi(value).equalsIgnoreCase(withoutDashes(expected))) {
            log.warning("MagixAuth: Mojang ha risposto con la skin del profilo " + diChi(value)
                    + " invece di quella di " + withoutDashes(expected) + ". Skin NON applicata.");
            return null;
        }
        return new String[]{value, m.group(2)};
    }

    /** Di chi e' questa texture, secondo quello che Mojang ci ha scritto dentro. */
    private static String diChi(String base64Value) {
        try {
            String inside = new String(Base64.getDecoder().decode(base64Value), StandardCharsets.UTF_8);
            Matcher m = PROFILE_ID.matcher(inside);
            return m.find() ? m.group(1) : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
