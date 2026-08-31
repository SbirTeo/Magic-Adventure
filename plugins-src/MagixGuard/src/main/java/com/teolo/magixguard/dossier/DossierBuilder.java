package com.teolo.magixguard.dossier;

import com.teolo.magixguard.GuardConfig;
import com.teolo.magixguard.analyze.EvidenceType;
import com.teolo.magixguard.analyze.LinkScorer;
import com.teolo.magixguard.db.GuardDao;
import com.teolo.magixguard.model.PlayerRef;
import com.teolo.magixguard.model.Rows;
import com.teolo.magixguard.util.Fmt;
import com.teolo.magixguard.util.Hashing;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Genera il dossier: il documento che lo staff consulta e, se serve, allega alla risposta a
 * un ricorso sul forum.
 *
 * Tre scelte che lo rendono difendibile invece che solo tecnico:
 *
 *  1. mostra SEMPRE anche le prove a discolpa. Un dossier che elenca solo gli indizi a carico
 *     e' un atto d'accusa, e alla prima obiezione seria crolla;
 *  2. dichiara i limiti del metodo in fondo al documento, in italiano comprensibile: chi legge
 *     deve sapere che cosa il dato NON dimostra;
 *  3. e' firmato. L'impronta del documento entra nel registro a catena del plugin, quindi si puo'
 *     dimostrare che il dossier esisteva gia' in quella forma prima della decisione, e che non e'
 *     stato ritoccato dopo.
 *
 * La versione pubblica maschera gli indirizzi IP: in un ricorso sul forum si mostra che due
 * account condividono la connessione, non l'indirizzo di casa di qualcuno.
 */
public final class DossierBuilder {

    private final GuardDao dao;
    private final GuardConfig config;
    private final LinkScorer scorer;
    private final File dataFolder;
    private final String pluginVersion;

    public DossierBuilder(GuardDao dao, GuardConfig config, LinkScorer scorer, File dataFolder, String pluginVersion) {
        this.dao = dao;
        this.config = config;
        this.scorer = scorer;
        this.dataFolder = dataFolder;
        this.pluginVersion = pluginVersion;
    }

    /** Documento generato: testo completo, impronta e file su disco. */
    public record Dossier(String text, String hash, Path file) {}

    /**
     * Dossier su una coppia di account.
     *
     * @param publicVersion true per la versione da allegare a un ricorso (IP mascherati)
     * @param actor         chi lo ha richiesto: finisce nel registro firmato
     */
    public Dossier pair(PlayerRef a, PlayerRef b, boolean publicVersion, String actor) throws SQLException, IOException {
        long now = System.currentTimeMillis();
        List<Rows.Evidence> evidence = dao.evidenceFor(a.id(), b.id());
        LinkScorer.Result result = scorer.score(evidence, now);
        Optional<Rows.Whitelist> whitelist = dao.getWhitelist(a.id(), b.id());

        StringBuilder sb = new StringBuilder();
        sb.append("# Dossier MagixGuard - ").append(a.name()).append(" / ").append(b.name()).append("\n\n");
        sb.append("Generato il ").append(Fmt.dateTime(now)).append(" (fuso orario Europe/Rome)").append("  \n");
        sb.append("Richiesto da: **").append(actor).append("**  \n");
        sb.append("Versione plugin: MagixGuard ").append(pluginVersion).append("  \n");
        sb.append("Tipo documento: ").append(publicVersion
                ? "**pubblico** (indirizzi IP mascherati, adatto da allegare a un ricorso)"
                : "**interno staff** (contiene dati di rete completi: non pubblicarlo cosi')").append("\n\n");

        // ---------------- esito ----------------
        sb.append("## Esito\n\n");
        sb.append("Punteggio di collegamento: **").append(Math.round(result.score())).append("/100**");
        sb.append(" (soglia di collegamento ").append(Math.round(config.linkThreshold));
        sb.append(", soglia di segnalazione ").append(Math.round(config.alertThreshold)).append(")\n\n");
        sb.append("> ").append(verdict(result)).append("\n\n");
        whitelist.ifPresent(w -> sb.append("Nota: questa coppia e' stata dichiarata legittima da **")
                .append(w.staff()).append("** il ").append(Fmt.dateTime(w.createdAt()))
                .append(w.reason() == null ? "" : " (motivo: " + w.reason() + ")").append(".\n\n"));

        // ---------------- account ----------------
        sb.append("## Account esaminati\n\n");
        sb.append("| | ").append(a.name()).append(" | ").append(b.name()).append(" |\n");
        sb.append("|---|---|---|\n");
        sb.append("| Primo accesso | ").append(Fmt.dateTime(a.firstSeen())).append(" | ")
                .append(Fmt.dateTime(b.firstSeen())).append(" |\n");
        sb.append("| Ultimo accesso | ").append(Fmt.dateTime(a.lastSeen())).append(" | ")
                .append(Fmt.dateTime(b.lastSeen())).append(" |\n");
        sb.append("| Accessi totali | ").append(a.sessionCount()).append(" | ")
                .append(b.sessionCount()).append(" |\n");
        sb.append("| Tempo di gioco | ").append(Fmt.duration(a.playtimeSeconds() * 1000))
                .append(" | ").append(Fmt.duration(b.playtimeSeconds() * 1000)).append(" |\n\n");

        // ---------------- prove ----------------
        appendEvidence(sb, result, false, "Indizi a carico");
        appendEvidence(sb, result, true, "Elementi a discolpa");

        // ---------------- cronologia ----------------
        sb.append("## Cronologia accessi\n\n");
        appendSessions(sb, a, publicVersion);
        appendSessions(sb, b, publicVersion);

        // ---------------- metodo e limiti ----------------
        appendMethodology(sb);

        String text = sb.toString();
        String hash = Hashing.sha256(text);
        String finalText = text + "\n---\n\nImpronta del documento (SHA-256): `" + hash + "`\n";

        Path file = write(finalText, a.name() + "-" + b.name(), publicVersion, now);
        if (config.dossierSign) {
            dao.appendAudit(actor, publicVersion ? "DOSSIER_PUBBLICO" : "DOSSIER_INTERNO",
                    a.name() + " <-> " + b.name(),
                    "punteggio=" + Math.round(result.score()) + "; impronta=" + hash + "; file=" + file.getFileName(),
                    now);
        }
        return new Dossier(finalText, hash, file);
    }

    /** Dossier su un singolo account: tutti i collegamenti sopra la soglia, dal piu' forte. */
    public Dossier profile(PlayerRef target, boolean publicVersion, String actor) throws SQLException, IOException {
        long now = System.currentTimeMillis();
        List<Rows.Link> links = dao.linksOf(target.id(), config.linkThreshold);

        StringBuilder sb = new StringBuilder();
        sb.append("# Dossier MagixGuard - profilo di ").append(target.name()).append("\n\n");
        sb.append("Generato il ").append(Fmt.dateTime(now)).append(" (fuso orario Europe/Rome)  \n");
        sb.append("Richiesto da: **").append(actor).append("**  \n");
        sb.append("Versione plugin: MagixGuard ").append(pluginVersion).append("\n\n");

        sb.append("## Account\n\n");
        sb.append("- Primo accesso: ").append(Fmt.dateTime(target.firstSeen())).append('\n');
        sb.append("- Ultimo accesso: ").append(Fmt.dateTime(target.lastSeen())).append('\n');
        sb.append("- Accessi totali: ").append(target.sessionCount()).append('\n');
        sb.append("- Tempo di gioco: ").append(Fmt.duration(target.playtimeSeconds() * 1000)).append("\n\n");

        sb.append("## Account collegati\n\n");
        if (links.isEmpty()) {
            sb.append("Nessun collegamento sopra la soglia di ").append(Math.round(config.linkThreshold))
                    .append(" punti.\n\n");
        } else {
            sb.append("| Account | Punteggio | Indizi principali | In whitelist |\n|---|---|---|---|\n");
            for (Rows.Link link : links) {
                long otherId = link.aId() == target.id() ? link.bId() : link.aId();
                Optional<PlayerRef> other = dao.findPlayer(otherId);
                if (other.isEmpty()) continue;
                LinkScorer.Result r = scorer.score(dao.evidenceFor(target.id(), otherId), now);
                sb.append("| ").append(other.get().name())
                        .append(" | ").append(Math.round(link.score()))
                        .append(" | ").append(topEvidence(r))
                        .append(" | ").append(dao.isWhitelisted(target.id(), otherId) ? "si" : "no")
                        .append(" |\n");
            }
            sb.append('\n');
        }

        sb.append("## Cronologia accessi\n\n");
        appendSessions(sb, target, publicVersion);
        appendMethodology(sb);

        String text = sb.toString();
        String hash = Hashing.sha256(text);
        String finalText = text + "\n---\n\nImpronta del documento (SHA-256): `" + hash + "`\n";
        Path file = write(finalText, target.name(), publicVersion, now);
        if (config.dossierSign) {
            dao.appendAudit(actor, publicVersion ? "DOSSIER_PUBBLICO" : "DOSSIER_INTERNO", target.name(),
                    "profilo; impronta=" + hash + "; file=" + file.getFileName(), now);
        }
        return new Dossier(finalText, hash, file);
    }

    // ============================== pezzi del documento ==============================

    private void appendEvidence(StringBuilder sb, LinkScorer.Result result, boolean exculpatory, String title) {
        List<LinkScorer.Line> lines = result.lines().stream()
                .filter(l -> l.type().exculpatory() == exculpatory)
                .toList();
        sb.append("## ").append(title).append("\n\n");
        if (lines.isEmpty()) {
            sb.append(exculpatory
                    ? "Nessun elemento a favore rilevato. Questo non significa che non ne esistano: "
                    + "significa che il plugin non ne ha osservati.\n\n"
                    : "Nessun indizio rilevato.\n\n");
            return;
        }
        sb.append("| Indizio | Peso | Osservazioni | Ultima volta | Dettaglio |\n|---|---|---|---|---|\n");
        for (LinkScorer.Line line : lines) {
            sb.append("| ").append(line.type().description())
                    .append(" | ").append(line.appliedWeight() >= 0 ? "+" : "").append(Math.round(line.appliedWeight()));
            if (Math.round(line.appliedWeight()) != Math.round(line.baseWeight())) {
                sb.append(" _(base ").append(Math.round(line.baseWeight())).append(")_");
            }
            sb.append(" | ").append(line.occurrences()).append(" volte")
                    .append(line.note() == null || line.note().isBlank() ? "" : " - " + line.note())
                    .append(" | ").append(Fmt.shortDateTime(line.lastSeen()))
                    .append(" | ").append(line.detail() == null ? "-" : line.detail())
                    .append(" |\n");
        }
        sb.append('\n');
    }

    private void appendSessions(StringBuilder sb, PlayerRef player, boolean publicVersion) throws SQLException {
        List<Rows.Session> sessions = dao.sessionsOf(player.id(), config.dossierMaxSessions);
        sb.append("### ").append(player.name()).append(" - ultimi ").append(sessions.size()).append(" accessi\n\n");
        if (sessions.isEmpty()) {
            sb.append("Nessun accesso registrato.\n\n");
            return;
        }
        sb.append("| Entrata | Uscita | Durata | IP | Rete | Client | Lingua | Ping |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (Rows.Session s : sessions) {
            long end = s.quitAt() == null ? System.currentTimeMillis() : s.quitAt();
            sb.append("| ").append(Fmt.shortDateTime(s.joinAt()))
                    .append(" | ").append(s.quitAt() == null ? "in corso" : Fmt.shortDateTime(s.quitAt()))
                    .append(" | ").append(Fmt.duration(end - s.joinAt()))
                    .append(" | ").append(ip(s.ip(), publicVersion))
                    .append(" | ").append(nvl(publicVersion ? maskRdns(s.rdns()) : s.rdns()))
                    .append(" | ").append(nvl(s.brand()))
                    .append(" | ").append(nvl(s.locale()))
                    .append(" | ").append(s.pingMedian() == null ? "-" : s.pingMedian() + " ms")
                    .append(" |\n");
        }
        sb.append('\n');
    }

    private void appendMethodology(StringBuilder sb) {
        sb.append("## Come vanno letti questi dati\n\n");
        sb.append("Il punteggio non e' una prova automatica: e' la somma di indizi, ognuno con il suo peso, ");
        sb.append("corretta da tre fattori dichiarati nella configurazione del plugin.\n\n");
        sb.append("- **Affollamento dell'indirizzo IP.** Un IP usato da molti account vale poco: sulle reti ");
        sb.append("mobili italiane (e su alcune fibre) centinaia di persone estranee fra loro escono con lo ");
        sb.append("stesso indirizzo pubblico. Il peso dell'indizio viene ridotto in proporzione.\n");
        sb.append("- **Eta' dell'indizio.** Gli indirizzi domestici cambiano: una coincidenza di sei mesi fa ");
        sb.append("pesa la meta' di una di oggi.\n");
        sb.append("- **Elementi a discolpa.** Due account visti online nello stesso momento sono, con ogni ");
        sb.append("probabilita', due persone diverse (tipicamente fratelli o coinquilini). Il punteggio scende.\n\n");
        sb.append("Limiti noti, dichiarati per correttezza:\n\n");
        sb.append("- il token di installazione (cookie) vive nella memoria del client: **la sua assenza non ");
        sb.append("dimostra nulla**, mentre la sua presenza identica su due account e' un indizio molto forte;\n");
        sb.append("- due fratelli che giocano dallo stesso computer condividono IP, rete e impronta del client: ");
        sb.append("in quel caso l'unico elemento che li distingue e' l'essere stati online insieme;\n");
        sb.append("- una VPN azzera gli indizi di rete, ma non tocca impronta del client, orari e cookie;\n");
        sb.append("- l'impronta del client cambia se il giocatore modifica le impostazioni o installa una mod.\n\n");
        sb.append("Gli indirizzi IP sono conservati in chiaro per ").append(config.retentionDays);
        sb.append(" giorni, poi restano solo in forma cifrata per i confronti.\n\n");
    }

    // ============================== utilita' ==============================

    private String verdict(LinkScorer.Result result) {
        double score = result.score();
        if (result.hasCertainty()) {
            return "I due account sono stati usati dalla stessa installazione di Minecraft. "
                    + "E' l'indizio piu' solido a disposizione di un server non premium.";
        }
        if (score >= config.alertThreshold) {
            return "Collegamento molto probabile: piu' indizi indipendenti puntano nella stessa direzione.";
        }
        if (score >= config.linkThreshold) {
            return "Collegamento probabile, ma non certo: valutare gli elementi a discolpa prima di decidere.";
        }
        return "Indizi insufficienti: gli elementi raccolti non bastano a sostenere che si tratti della stessa persona.";
    }

    private String topEvidence(LinkScorer.Result result) {
        return result.lines().stream()
                .filter(l -> l.appliedWeight() > 0)
                .sorted((x, y) -> Double.compare(y.appliedWeight(), x.appliedWeight()))
                .limit(3)
                .map(l -> l.type().description())
                .reduce((x, y) -> x + "; " + y)
                .orElse("-");
    }

    private String ip(String ip, boolean publicVersion) {
        if (ip == null) return "_anonimizzato_";
        return publicVersion ? Hashing.maskIp(ip, config.publicIpOctets) : ip;
    }

    /** Nella versione pubblica del rDNS resta solo il dominio dell'operatore, non la parte che identifica la linea. */
    private static String maskRdns(String rdns) {
        if (rdns == null) return null;
        String[] parts = rdns.split("\\.");
        if (parts.length <= 2) return rdns;
        return "x." + String.join(".", java.util.Arrays.copyOfRange(parts, parts.length - 2, parts.length));
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private Path write(String text, String subject, boolean publicVersion, long now) throws IOException {
        File folder = new File(dataFolder, config.dossierFolder);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("impossibile creare la cartella dei dossier: " + folder.getAbsolutePath());
        }
        String safeSubject = subject.replaceAll("[^A-Za-z0-9_-]", "_");
        String name = Fmt.fileStamp(now) + "_" + safeSubject + (publicVersion ? "_pubblico" : "_staff") + ".md";
        Path path = new File(folder, name).toPath();
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }
}
