package com.teolo.magixguard.chat;

import com.teolo.magixguard.sanzioni.Rilevatore;
import com.teolo.magixguard.sanzioni.Testo;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Il filtro della chat: quattro cose diverse, con quattro reazioni diverse.
 *
 * <table>
 *   <tr><th>Cosa</th><th>Cosa succede al messaggio</th><th>Perche'</th></tr>
 *   <tr><td>Pubblicita'</td><td>bloccato</td>
 *       <td>l'indirizzo di un altro server non deve comparire nemmeno un istante</td></tr>
 *   <tr><td>Dati personali</td><td>bloccato</td><td>protezione dei minori</td></tr>
 *   <tr><td>Insulti</td><td>censurato, passa</td>
 *       <td>il contesto della lite resta leggibile allo staff</td></tr>
 *   <tr><td>Spam</td><td>bloccato in silenzio</td>
 *       <td>chi provoca non deve vedere l'effetto che fa</td></tr>
 * </table>
 *
 * <p>In tutti e quattro i casi il messaggio <b>originale integro</b> finisce nelle prove, con
 * le righe di chat intorno: e' quello che rende una violazione utilizzabile in un ricorso e non
 * un'accusa a memoria.</p>
 *
 * <p>Gira in <b>priorita' bassa</b>, dopo il controllo del silenziamento e prima che qualunque
 * plugin formatti il messaggio: quello che si blocca qui non arriva a nessuno.</p>
 */
public final class FiltroChat implements Listener {

    private static final PlainTextComponentSerializer TESTO = PlainTextComponentSerializer.plainText();

    /** Indirizzi IP scritti per esteso, con o senza porta. */
    private static final Pattern IP = Pattern.compile("\\b\\d{1,3}[.,\\s]\\d{1,3}[.,\\s]\\d{1,3}[.,\\s]\\d{1,3}\\b");
    /** Domini con un'estensione plausibile: il pezzo che serve per raggiungere un altro server. */
    private static final Pattern DOMINIO = Pattern.compile(
            "\\b[a-z0-9][a-z0-9-]{1,40}\\s?[.,]\\s?(it|com|net|org|eu|gg|io|me|xyz|online|fun|shop|club|top|site)\\b");
    /** Inviti Discord, che di fatto sono un altro server anche quando non lo sembrano. */
    private static final Pattern DISCORD = Pattern.compile("(discord\\s?[.,]\\s?(gg|com)|discordapp)");
    /** Numeri di telefono italiani, anche spezzati con spazi o punti. */
    private static final Pattern TELEFONO = Pattern.compile("\\b(\\+?39[\\s.-]?)?3\\d{2}[\\s.-]?\\d{3}[\\s.-]?\\d{3,4}\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b[a-z0-9._%-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b");

    private final Rilevatore rilevatore;
    private final MemoriaChat memoria;

    private final boolean attivo;
    private final boolean censuraInsulti;
    private final int spamFinestraSecondi;
    private final int spamMassimo;
    private final double spamSomiglianza;
    private final int maiuscolePercento;
    private final int maiuscoleLunghezzaMinima;
    private final boolean insultiAttivi;
    private final boolean pubblicitaAttiva;
    private final boolean datiAttivi;
    private final List<String> parolacce = new ArrayList<>();
    private final List<String> dominiConsentiti = new ArrayList<>();
    private final List<String> frasiAdescamento = new ArrayList<>();

    public FiltroChat(Rilevatore rilevatore, ConfigurationSection cfg) {
        this.rilevatore = rilevatore;
        this.attivo = cfg == null || cfg.getBoolean("attivo", true);
        this.memoria = new MemoriaChat(cfg == null ? 6 : cfg.getInt("contesto-righe", 6));

        this.spamFinestraSecondi = cfg == null ? 8 : Math.max(2, cfg.getInt("spam/finestra-secondi", 8));
        this.spamMassimo = cfg == null ? 4 : Math.max(2, cfg.getInt("spam/massimo-messaggi", 4));
        this.spamSomiglianza = cfg == null ? 0.85 : cfg.getDouble("spam/somiglianza", 0.85);
        this.maiuscolePercento = cfg == null ? 70 : cfg.getInt("spam/maiuscole-percento", 70);
        this.maiuscoleLunghezzaMinima = cfg == null ? 8 : cfg.getInt("spam/maiuscole-lunghezza-minima", 8);

        this.insultiAttivi = cfg == null || cfg.getBoolean("insulti/attivo", true);
        this.censuraInsulti = cfg == null || cfg.getBoolean("insulti/censura", true);
        this.pubblicitaAttiva = cfg == null || cfg.getBoolean("pubblicita/attivo", true);
        this.datiAttivi = cfg == null || cfg.getBoolean("dati-personali/attivo", true);

        if (cfg != null) {
            for (String p : cfg.getStringList("insulti/parole")) {
                parolacce.add(Normalizza.compatta(p));
            }
            for (String d : cfg.getStringList("pubblicita/domini-consentiti")) {
                dominiConsentiti.add(d.toLowerCase().trim());
            }
            for (String f : cfg.getStringList("dati-personali/frasi")) {
                frasiAdescamento.add(Normalizza.aParole(f));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void suChat(AsyncChatEvent e) {
        if (!attivo) {
            return;
        }
        Player p = e.getPlayer();
        String originale = TESTO.serialize(e.message());
        if (originale.isBlank()) {
            return;
        }
        // Lo staff non passa dal filtro: deve poter citare un indirizzo o una parola per
        // spiegare perche' e' vietata, senza sanzionarsi da solo.
        if (p.hasPermission("magixguard.chat.bypass")) {
            memoria.aggiungi(p.getUniqueId(), p.getName(), originale);
            return;
        }

        String aParole = Normalizza.aParole(originale);
        String compatta = Normalizza.compatta(originale);
        String contesto = memoria.contesto();

        // --- 1. Pubblicita': si blocca, ed e' la piu' grave delle quattro ---
        if (pubblicitaAttiva) {
            String trovato = cercaPubblicita(aParole, compatta);
            if (trovato != null) {
                e.setCancelled(true);
                p.sendMessage(Testo.msg("&#FF6B6BNon si pubblicizzano altri server."));
                segnala(p, "chat.pubblicita", originale, "Riconosciuto: " + trovato, contesto);
                return;
            }
        }

        // --- 2. Dati personali: si blocca, ma non sanziona mai da solo ---
        if (datiAttivi) {
            String trovato = cercaDatiPersonali(originale, aParole);
            if (trovato != null) {
                e.setCancelled(true);
                p.sendMessage(Testo.msg("&#FFD166Non scrivere dati personali in chat pubblica. "
                        + "&7E' per la tua sicurezza."));
                segnala(p, "chat.dati-personali", originale, "Riconosciuto: " + trovato, contesto);
                return;
            }
        }

        // --- 3. Spam: si blocca in silenzio (lo vede solo chi l'ha scritto) ---
        String spam = cercaSpam(p, originale, aParole);
        if (spam != null) {
            e.setCancelled(true);
            p.sendMessage(Testo.msg("&7Rallenta: &f" + spam.toLowerCase() + "&7."));
            segnala(p, "chat.spam", originale, "Riconosciuto: " + spam, contesto);
            memoria.aggiungi(p.getUniqueId(), p.getName(), originale);
            return;
        }

        // --- 4. Insulti: passa censurato, cosi' la lite resta leggibile ---
        if (insultiAttivi) {
            List<String> trovate = cercaInsulti(aParole, compatta);
            if (!trovate.isEmpty()) {
                if (censuraInsulti) {
                    e.message(Component.text(censura(originale, trovate)));
                } else {
                    e.setCancelled(true);
                }
                segnala(p, "chat.insulti", originale, "Riconosciute: " + String.join(", ", trovate), contesto);
            }
        }

        memoria.aggiungi(p.getUniqueId(), p.getName(), originale);
    }

    @EventHandler
    public void suUscita(PlayerQuitEvent e) {
        memoria.dimentica(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ riconoscimenti

    /** Ritorna cosa ha fatto scattare la pubblicita', o null. */
    private String cercaPubblicita(String aParole, String compatta) {
        Matcher m = IP.matcher(aParole);
        if (m.find()) {
            return "indirizzo IP (" + m.group() + ")";
        }
        if (DISCORD.matcher(aParole).find() || DISCORD.matcher(compatta).find()) {
            return "invito Discord";
        }
        m = DOMINIO.matcher(aParole);
        while (m.find()) {
            String dominio = m.group().replace(" ", "").replace(",", ".");
            if (!consentito(dominio)) {
                return "dominio (" + dominio + ")";
            }
        }
        return null;
    }

    /** I nostri indirizzi non sono pubblicita': si possono nominare. */
    private boolean consentito(String dominio) {
        for (String ok : dominiConsentiti) {
            if (dominio.equals(ok) || dominio.endsWith("." + ok)) {
                return true;
            }
        }
        return false;
    }

    /** Ritorna che tipo di dato personale e' stato riconosciuto, o null. */
    private String cercaDatiPersonali(String originale, String aParole) {
        if (TELEFONO.matcher(originale).find()) {
            return "numero di telefono";
        }
        if (EMAIL.matcher(originale.toLowerCase()).find()) {
            return "indirizzo email";
        }
        for (String frase : frasiAdescamento) {
            if (!frase.isBlank() && aParole.contains(frase)) {
                return "frase sorvegliata (\"" + frase + "\")";
            }
        }
        return null;
    }

    /** Ritorna il tipo di spam riconosciuto, o null. */
    private String cercaSpam(Player p, String originale, String aParole) {
        int recenti = memoria.quantiNegliUltimi(p.getUniqueId(), spamFinestraSecondi * 1000L);
        if (recenti >= spamMassimo) {
            return "Troppi messaggi in pochi secondi";
        }
        String ultimo = memoria.ultimoDi(p.getUniqueId());
        if (ultimo != null && Normalizza.somiglianza(Normalizza.aParole(ultimo), aParole) >= spamSomiglianza) {
            return "Messaggio ripetuto";
        }
        if (originale.length() >= maiuscoleLunghezzaMinima
                && Normalizza.percentualeMaiuscole(originale) >= maiuscolePercento) {
            return "Troppe maiuscole";
        }
        return null;
    }

    /**
     * Le parole del dizionario trovate nel messaggio.
     *
     * <p>Il confronto e' su parola intera nella forma a parole — cosi' "culo" non compare dentro
     * "calcolo" — e sulla forma compatta solo per le parole lunghe almeno cinque lettere, dove
     * il rischio di falso positivo e' basso e il trucco della spaziatura e' frequente.</p>
     */
    private List<String> cercaInsulti(String aParole, String compatta) {
        List<String> trovate = new ArrayList<>();
        for (String parola : parolacce) {
            if (parola.isBlank()) {
                continue;
            }
            boolean c = false;
            for (String pezzo : aParole.split(" ")) {
                if (pezzo.equals(parola) || (pezzo.length() > parola.length() + 2 && pezzo.startsWith(parola))) {
                    c = true;
                    break;
                }
            }
            if (!c && parola.length() >= 5 && compatta.contains(parola)) {
                c = true;
            }
            if (c) {
                trovate.add(parola);
            }
        }
        return trovate;
    }

    /**
     * Sostituisce con gli asterischi le parole riconosciute, lasciando in piedi il resto della
     * frase: serve a poter continuare a leggere di cosa stavano discutendo.
     */
    private String censura(String originale, List<String> trovate) {
        String[] pezzi = originale.split(" ");
        for (int i = 0; i < pezzi.length; i++) {
            String nudo = Normalizza.compatta(pezzi[i]);
            for (String parola : trovate) {
                if (nudo.contains(parola)) {
                    pezzi[i] = "*".repeat(Math.max(3, pezzi[i].length()));
                    break;
                }
            }
        }
        return String.join(" ", pezzi);
    }

    // ------------------------------------------------------------------ prove

    /** Manda la violazione al registro, con il messaggio originale e il contesto. */
    private void segnala(Player p, String categoria, String originale, String riconosciuto, String contesto) {
        String dettaglio = "Messaggio: " + originale + "\n"
                + riconosciuto + "\n\n"
                + contesto;
        rilevatore.rileva(p.getUniqueId(), p.getName(), categoria, "chat", dettaglio);
    }
}
