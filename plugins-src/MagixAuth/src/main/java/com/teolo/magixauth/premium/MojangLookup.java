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
    private final Map<String, Voce> skinViste = new ConcurrentHashMap<>();

    /** Una skin in cache, con il momento in cui va richiesta di nuovo. */
    private record Voce(String[] skin, long scadenza) {
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
    public record Ritrovata(String[] skin, boolean fallita, String motivo) {
        static Ritrovata riuscita(String[] skin) {
            return new Ritrovata(skin, false, "");
        }
        static Ritrovata fallita(String motivo) {
            return new Ritrovata(null, true, motivo);
        }
    }

    /** Com'e' andata una singola chiamata. */
    private enum Esito { OK, ASSENTE, FALLITA }

    private record Risposta(Esito esito, String corpo, String motivo) {}

    public MojangLookup(int timeoutMillis, int minutiCache, Logger log) {
        this.timeoutMillis = timeoutMillis;
        this.cacheMillis = Math.max(1, minutiCache) * 60_000L;
        this.log = log;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
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
    public Ritrovata skinDi(UUID uuidAccount, String nome) {
        if (nome == null) {
            return Ritrovata.riuscita(null);
        }
        String chiave = nome.toLowerCase(Locale.ROOT);
        Voce inCache = skinViste.get(chiave);
        if (inCache != null && inCache.valida()) {
            return Ritrovata.riuscita(inCache.skin());
        }

        // La scorciatoia: se l'UUID dell'account e' un UUID Mojang (versione 4, mentre
        // quelli ricavati dal nome in offline mode sono di versione 3) il profilo si puo'
        // chiedere subito, senza passare dal nome.
        if (uuidAccount != null && uuidAccount.version() == 4) {
            Risposta profilo = chiama(PROFILO + senzaTrattini(uuidAccount) + "?unsigned=false");
            if (profilo.esito() == Esito.OK) {
                return ricorda(chiave, texture(profilo.corpo(), uuidAccount));
            }
            if (profilo.esito() == Esito.FALLITA) {
                return Ritrovata.fallita(profilo.motivo());
            }
            // ASSENTE: quell'UUID non esiste piu'. Si riprova per nome.
        }

        Risposta cercato = chiama(ENDPOINT + nome);
        if (cercato.esito() == Esito.FALLITA) {
            return Ritrovata.fallita(cercato.motivo());
        }
        if (cercato.esito() == Esito.ASSENTE) {
            // Nome libero: nessun account premium si chiama cosi'. E' una risposta vera, e
            // come tale si puo' tenere da parte.
            return ricorda(chiave, null);
        }
        UUID premium = uuidDa(cercato.corpo());
        if (premium == null) {
            return ricorda(chiave, null);
        }
        Risposta profilo = chiama(PROFILO + senzaTrattini(premium) + "?unsigned=false");
        if (profilo.esito() == Esito.FALLITA) {
            return Ritrovata.fallita(profilo.motivo());
        }
        return ricorda(chiave, profilo.esito() == Esito.OK ? texture(profilo.corpo(), premium) : null);
    }

    /** L'UUID Mojang di un nome, se il nome e' un account premium. */
    public UUID cerca(String nome) {
        if (nome == null || !nome.matches("[A-Za-z0-9_]{3,16}")) {
            return null;
        }
        Risposta r = chiama(ENDPOINT + nome);
        return r.esito() == Esito.OK ? uuidDa(r.corpo()) : null;
    }

    /** La skin di un UUID, chiesta adesso, senza passare dalla cache. */
    public String[] skin(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        Risposta r = chiama(PROFILO + senzaTrattini(uuid) + "?unsigned=false");
        return r.esito() == Esito.OK ? texture(r.corpo(), uuid) : null;
    }

    private Ritrovata ricorda(String chiave, String[] skin) {
        skinViste.put(chiave, new Voce(skin, System.currentTimeMillis() + cacheMillis));
        return Ritrovata.riuscita(skin);
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
    public void scalda() {
        chiama(PROFILO + "00000000000000000000000000000000");
    }

    /**
     * Una chiamata a Mojang, con la differenza fra "ha detto di no" e "non ha risposto".
     *
     * Non lancia mai: chi la usa non deve poter impedire a nessuno di entrare per colpa di
     * un servizio esterno finito sul percorso di ingresso al server.
     */
    private Risposta chiama(String url) {
        Risposta primo = tentativo(url);
        if (primo.esito() != Esito.FALLITA) {
            return primo;
        }
        // Un secondo tentativo subito: quasi tutti i guasti visti qui sono del primo colpo
        // (stretta di mano TLS chiusa dal CDN, collegamento ancora freddo). Riprovare costa
        // molto meno che lasciare entrare qualcuno con la faccia sbagliata.
        Risposta secondo = tentativo(url);
        return secondo.esito() == Esito.FALLITA
                ? new Risposta(Esito.FALLITA, "", secondo.motivo() + " (al secondo tentativo)")
                : secondo;
    }

    private Risposta tentativo(String url) {
        try {
            HttpRequest richiesta = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("User-Agent", "MagixAuth (magicadventure.it)")
                    .GET()
                    .build();
            HttpResponse<String> risposta = client.send(richiesta, HttpResponse.BodyHandlers.ofString());
            int codice = risposta.statusCode();
            if (codice == 200) {
                return new Risposta(Esito.OK, risposta.body(), "");
            }
            // 204 e 404 sono una risposta: quel nome non esiste. Tutto il resto (429 troppe
            // richieste, 5xx, ...) e' un guaio momentaneo di Mojang, non un dato sul nome.
            return new Risposta(codice == 204 || codice == 404 ? Esito.ASSENTE : Esito.FALLITA, "",
                    "risposta " + codice);
        } catch (Exception e) {
            return new Risposta(Esito.FALLITA, "", e.toString());
        }
    }

    private static String senzaTrattini(UUID uuid) {
        return uuid.toString().replace("-", "");
    }

    private static UUID uuidDa(String corpo) {
        Matcher m = ID.matcher(corpo);
        if (!m.find()) {
            return null;
        }
        String nudo = m.group(1);
        return UUID.fromString(
                nudo.substring(0, 8) + "-" + nudo.substring(8, 12) + "-" +
                nudo.substring(12, 16) + "-" + nudo.substring(16, 20) + "-" + nudo.substring(20));
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
    private String[] texture(String corpo, UUID atteso) {
        Matcher m = TEXTURES.matcher(corpo);
        if (!m.find()) {
            return null;
        }
        String valore = m.group(1);
        if (atteso != null && !diChi(valore).isEmpty()
                && !diChi(valore).equalsIgnoreCase(senzaTrattini(atteso))) {
            log.warning("MagixAuth: Mojang ha risposto con la skin del profilo " + diChi(valore)
                    + " invece di quella di " + senzaTrattini(atteso) + ". Skin NON applicata.");
            return null;
        }
        return new String[]{valore, m.group(2)};
    }

    /** Di chi e' questa texture, secondo quello che Mojang ci ha scritto dentro. */
    private static String diChi(String valoreBase64) {
        try {
            String dentro = new String(Base64.getDecoder().decode(valoreBase64), StandardCharsets.UTF_8);
            Matcher m = PROFILE_ID.matcher(dentro);
            return m.find() ? m.group(1) : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
