package com.teolo.magixfactions.manage;

import com.teolo.magixfactions.model.Faction;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * PUNTEGGIO di fazione e CLASSIFICA (/f top).
 *
 * <h2>Metodo (RELATIVO al migliore)</h2>
 * Una "Top fazioni" che ordina per un solo numero (territori, o soldi) e' fuorviante. Qui il punteggio e'
 * la SINTESI di piu' caratteristiche, ognuna misurata IN RELAZIONE alla fazione migliore:
 * <ul>
 *   <li>in ogni caratteristica la fazione col valore piu' alto vale <b>1</b>; le altre valgono
 *       {@code proprio valore / valore del migliore} (quindi fra 0 e 1);</li>
 *   <li>ogni frazione e' moltiplicata per il PESO della voce ({@code score.weights.<voce>}, default 1):
 *       col peso 1 la voce vale al massimo 1, col peso 2 il doppio. Il punteggio e' la <b>somma</b> dei
 *       contributi (peso × frazione); il massimo possibile e' la SOMMA DEI PESI (una fazione prima in
 *       tutto). Coi pesi tutti a 1 il massimo e' il numero di caratteristiche.</li>
 * </ul>
 * Niente valori di riferimento e nessun tetto artificiale: il massimo di una voce e' semplicemente il suo
 * peso. Conseguenza da sapere: il punteggio e' RELATIVO, cioe' cambia anche se una fazione non fa nulla ma
 * un'altra alza il record di una voce.
 *
 * <h2>Medie nel tempo</h2>
 * Banca e Potenza entrano, di default, con la loro MEDIA nel tempo ("giacenza media", "potenza media"),
 * cosi' un picco momentaneo non gonfia la classifica; con {@code score.bank-value}/{@code score.power-value}
 * a {@code current} si usa invece il valore attuale. La media si tiene con un integrale {@code valore ×
 * secondi} accumulato sulla fazione (colonne {@code factions.*_avg_accum}, {@code score_sampled_at},
 * {@code score_since}) e aggiornato da un campionatore periodico. Territori, Membri e Longevita' usano il
 * valore corrente (numero di chunk, numero di membri, giorni dalla creazione).
 */
public final class ScoreManager {

    /** Le caratteristiche, in ordine di visualizzazione. */
    private static final String[] KEYS = {"land", "members", "bank", "longevity", "power", "kills", "value"};

    private final JavaPlugin plugin;
    private final FactionManager fm;
    private final PowerManager power;
    private final ClaimManager claims;
    private final PlayerStatsManager stats;

    public ScoreManager(JavaPlugin plugin, FactionManager fm, PowerManager power, ClaimManager claims,
                        PlayerStatsManager stats) {
        this.plugin = plugin;
        this.fm = fm;
        this.power = power;
        this.claims = claims;
        this.stats = stats;
    }

    // ----------------------------- CONFIG -----------------------------

    /** Peso della caratteristica (score.weights.<voce>, default 1). E' il MASSIMO che quella voce puo'
     *  dare (quando sei tu il migliore): con peso 1 vale al massimo 1, con peso 2 vale il doppio. Peso 0
     *  la esclude dal punteggio. */
    private double weight(String key) {
        return Math.max(0, plugin.getConfig().getDouble("score.weights." + key, 1.0));
    }

    /** Banca: media nel tempo (default) o valore attuale? (score.bank-value: average|current). */
    private boolean bankAverage() {
        return !"current".equalsIgnoreCase(plugin.getConfig().getString("score.bank-value", "average"));
    }

    /** Potenza: media nel tempo (default) o valore attuale? (score.power-value: average|current). */
    private boolean powerAverage() {
        return !"current".equalsIgnoreCase(plugin.getConfig().getString("score.power-value", "average"));
    }

    /** Giorni di assenza TOTALE (tutti i membri) oltre i quali una fazione e' considerata INATTIVA e
     *  oscurata dalla classifica. 0 = disattivato (tutte in classifica). (score.inactive-days) */
    public int inactiveDays() {
        return Math.max(0, plugin.getConfig().getInt("score.inactive-days", 7));
    }

    /**
     * La fazione e' ATTIVA (compare in classifica e fa da riferimento agli altri)? Lo e' se almeno un
     * membro e' online adesso, o si e' collegato negli ultimi {@link #inactiveDays()} giorni. Se TUTTI i
     * membri mancano da piu' di tanto (o non ha membri) e' inattiva -> oscurata. Con inactive-days a 0 la
     * funzione e' spenta e sono tutte attive.
     */
    public boolean isActive(Faction f) {
        int days = inactiveDays();
        if (days <= 0) return true;
        if (f.getMembers().isEmpty()) return false;
        long threshold = System.currentTimeMillis() - days * 86_400_000L;
        for (UUID u : f.getMembers().keySet()) {
            if (Bukkit.getPlayer(u) != null) return true;      // qualcuno online = attiva
            if (power.lastLogin(u) >= threshold) return true;  // visto di recente
        }
        return false;
    }

    /** Almeno un membro e' collegato ADESSO. La giacenza media della banca avanza solo in questi momenti
     *  (conta i soldi tenuti MENTRE si gioca); la potenza invece si media sul tempo reale. */
    private boolean anyMemberOnline(Faction f) {
        for (UUID u : f.getMembers().keySet()) if (Bukkit.getPlayer(u) != null) return true;
        return false;
    }

    /** Come {@link #anyMemberOnline} ma IGNORANDO un membro (quello che sta entrando/uscendo): serve a
     *  sapere se la fazione era gia' online PRIMA di quel giocatore, per attribuire correttamente
     *  l'intervallo appena trascorso. */
    private boolean anyMemberOnlineExcept(Faction f, UUID except) {
        for (UUID u : f.getMembers().keySet()) if (!u.equals(except) && Bukkit.getPlayer(u) != null) return true;
        return false;
    }

    // --------------------------- INTEGRALE ESATTO ----------------------
    /**
     * "Chiude" l'intervallo aperto [scoreSampledAt, ora] accreditando i valori TENUTI FINORA (saldo e
     * potenza correnti) per la sua durata, poi sposta scoreSampledAt a ora. Chiamandolo a ogni cambio di
     * stato (cambio saldo, ingresso/uscita di un membro) invece che solo ai campioni periodici, la media
     * diventa un integrale ESATTO: ogni saldo pesa esattamente per il tempo in cui e' stato tenuto (niente
     * approssimazione all'"estremo destro" che poteva far muovere la media nel verso sbagliato).
     * <p>La banca accredita solo se {@code online} (i soldi tenuti a server vuoto non contano); la potenza
     * sempre (deve calare anche da offline). {@code online} lo decide il chiamante in base allo stato che
     * valeva DURANTE l'intervallo appena chiuso.
     */
    public void flush(Faction f, boolean online) {
        long now = System.currentTimeMillis();
        double dt = Math.max(0, now - f.getScoreSampledAt()) / 1000.0;
        if (dt <= 0) return;
        f.setPowerAvgAccum(f.getPowerAvgAccum() + power.factionPower(f) * dt);
        if (online) {
            f.setBankAvgAccum(f.getBankAvgAccum() + f.getBank() * dt);
            f.setBankActiveSeconds(f.getBankActiveSeconds() + dt);
        }
        f.setScoreSampledAt(now);
    }

    /** Flush con lo stato online ATTUALE della fazione (per i campioni periodici e i cambi saldo). */
    public void flush(Faction f) { flush(f, anyMemberOnline(f)); }

    /** Un membro ENTRA: chiude l'intervallo appena trascorso attribuendolo allo stato di PRIMA (online solo
     *  se c'erano gia' altri membri collegati), cosi' il tempo da offline non viene contato per la banca. */
    public void onMemberJoin(Faction f, UUID joining) {
        if (f != null) flush(f, anyMemberOnlineExcept(f, joining));
    }

    /** Un membro ESCE: chiude l'intervallo appena trascorso come ONLINE (durante l'evento di quit il
     *  giocatore risulta ancora collegato), cosi' il tempo giocato viene accreditato prima che se ne vada. */
    public void onMemberQuit(Faction f) {
        if (f != null) flush(f, anyMemberOnline(f));
    }

    public int sampleIntervalSeconds() {
        return Math.max(5, plugin.getConfig().getInt("score.sample-interval-seconds", 300));
    }

    public int topSize() {
        return Math.max(1, plugin.getConfig().getInt("score.top-size", 10));
    }

    private int decimals() {
        return Math.max(0, Math.min(4, plugin.getConfig().getInt("score.decimals", 2)));
    }

    /** Le caratteristiche attive (peso > 0), in ordine. */
    private List<String> activeKeys() {
        List<String> out = new ArrayList<>();
        for (String k : KEYS) if (weight(k) > 0) out.add(k);
        return out;
    }

    /** Il punteggio massimo raggiungibile = somma dei pesi (una fazione prima in TUTTE le voci). */
    public double maxScore() {
        double m = 0;
        for (String k : activeKeys()) m += weight(k);
        return m;
    }

    /** Il massimo come testo pulito (senza decimali inutili), per il "/ max" mostrato. */
    public String maxScoreStr() { return trimNum(maxScore()); }

    // --------------------------- MEDIE NEL TEMPO -----------------------

    private double openPowerAccum(Faction f, long now) {
        double dtSec = Math.max(0, now - f.getScoreSampledAt()) / 1000.0;
        return f.getPowerAvgAccum() + power.factionPower(f) * dtSec;
    }

    /**
     * Giacenza MEDIA della banca. Si media SOLO sul tempo in cui c'e' stato almeno un membro ONLINE: i
     * soldi tenuti mentre nessuno gioca non contano (il "tempo si ferma" per la banca). Cosi' non si puo'
     * gonfiare la media parcheggiando denaro e restando offline. Denominatore = secondi con qualcuno
     * online ({@code bank_active_seconds}), non il tempo reale.
     */
    public double averageBank(Faction f) {
        long now = System.currentTimeMillis();
        double accum = f.getBankAvgAccum();
        double activeSec = f.getBankActiveSeconds();
        if (anyMemberOnline(f)) {   // intervallo aperto: conta solo se qualcuno e' online
            double dt = Math.max(0, now - f.getScoreSampledAt()) / 1000.0;
            accum += f.getBank() * dt;
            activeSec += dt;
        }
        if (activeSec < sampleIntervalSeconds()) return f.getBank();   // troppo poco tempo online: valore attuale
        return accum / activeSec;
    }

    /** Potenza MEDIA (totale di fazione) nel tempo dalla creazione. */
    public double averagePower(Faction f) {
        long now = System.currentTimeMillis();
        double windowSec = (now - f.getScoreSince()) / 1000.0;
        if (windowSec < sampleIntervalSeconds()) return power.factionPower(f);
        return openPowerAccum(f, now) / windowSec;
    }

    /** Eta' della fazione in giorni (dalla data di creazione reale). */
    public double ageDays(Faction f) {
        return Math.max(0, System.currentTimeMillis() - f.getCreatedAt()) / 86_400_000.0;
    }

    // --------------------------- VALORI DELLE VOCI ---------------------

    /** Metrica combat che entra nel punteggio: "kills" (uccisioni valide, default) o "kd" (rapporto K/D).
     *  Il K/D e' un rapporto instabile per un metodo RELATIVO al migliore (una fazione con poche morti
     *  schiaccerebbe tutte le altre), quindi il default e' il volume di uccisioni. (score.combat-metric) */
    private boolean combatIsKd() {
        return "kd".equalsIgnoreCase(plugin.getConfig().getString("score.combat-metric", "kills"));
    }

    /** Il valore grezzo di una caratteristica per una fazione (rispettando media/attuale del config). */
    private double valueOf(String key, Faction f) {
        return switch (key) {
            case "land" -> claims.count(f.getId());
            case "members" -> f.size();
            case "bank" -> bankAverage() ? averageBank(f) : f.getBank();
            case "longevity" -> ageDays(f);
            case "power" -> powerAverage() ? averagePower(f) : power.factionPower(f);
            case "kills" -> combatIsKd()
                    ? (stats.factionDeaths(f) <= 0 ? stats.factionKills(f)
                                                   : (double) stats.factionKills(f) / stats.factionDeaths(f))
                    : stats.factionKills(f);
            case "value" -> claims.value(f.getId());
            default -> 0;
        };
    }

    /** Etichetta leggibile della voce (Banca/Potenza dicono "(media)" quando lo sono). */
    private String label(String key) {
        return switch (key) {
            case "land" -> "Territori";
            case "members" -> "Membri";
            case "bank" -> bankAverage() ? "Banca (media)" : "Banca";
            case "longevity" -> "Longevità";
            case "power" -> powerAverage() ? "Potenza (media)" : "Potenza";
            case "kills" -> combatIsKd() ? "K/D" : "Uccisioni";
            case "value" -> "Valore";
            default -> key;
        };
    }

    /** Testo formattato del valore grezzo (interi, soldi con le migliaia, giorni con la "g"). */
    private String valueText(String key, double v) {
        return switch (key) {
            case "bank", "value" -> moneyText(v);
            case "longevity" -> daysText(v);
            case "kills" -> combatIsKd() ? kdText(v) : intText(v);
            default -> intText(v);
        };
    }

    private static String intText(double v) { return String.valueOf(Math.round(v)); }
    private static String moneyText(double v) { return String.format(Locale.ITALY, "%,.0f", v); }
    private static String daysText(double v) { return Math.round(v) + "g"; }
    private static String kdText(double v) { return String.format(Locale.ITALY, "%.2f", v); }

    // ------------------------------ MASSIMI ----------------------------

    /** Il migliore di una caratteristica: il valore massimo e la fazione che lo detiene (fa da riferimento). */
    private static final class MaxInfo {
        double value = 0;
        long factionId = 0;
        String name = "";
    }

    /** Per ogni caratteristica attiva, il MIGLIORE (valore massimo + fazione che lo tiene) fra tutte le
     *  fazioni ATTIVE. Le inattive (oscurate) NON fanno da riferimento, cosi' una fazione morta con la
     *  banca piena non falsa il metro delle altre. */
    private Map<String, MaxInfo> computeMaxes() {
        Map<String, MaxInfo> max = new LinkedHashMap<>();
        for (String k : activeKeys()) max.put(k, new MaxInfo());
        for (Faction f : fm.all()) {
            if (!isActive(f)) continue;
            for (Map.Entry<String, MaxInfo> e : max.entrySet()) {
                double v = Math.max(0, valueOf(e.getKey(), f));   // i negativi (Potenza) non contano
                MaxInfo mi = e.getValue();
                if (v > mi.value) { mi.value = v; mi.factionId = f.getId(); mi.name = f.getName(); }
            }
        }
        return max;
    }

    // ------------------------------ PUNTEGGIO --------------------------

    /**
     * Il dettaglio del punteggio, una voce per caratteristica: valore grezzo e FRAZIONE (0-1) rispetto al
     * migliore. Serve al TOOLTIP che spiega "come si arriva a quel punteggio". La somma delle frazioni =
     * {@link #score}.
     */
    public List<Component> breakdown(Faction f) {
        return breakdown(f, computeMaxes());
    }

    private List<Component> breakdown(Faction f, Map<String, MaxInfo> maxes) {
        List<Component> out = new ArrayList<>();
        for (String k : activeKeys()) {
            double v = Math.max(0, valueOf(k, f));
            MaxInfo mi = maxes.get(k);
            double mx = mi == null ? 0 : mi.value;
            // Clamp a 1: una fazione INATTIVA (fuori dai massimi) potrebbe superare il migliore ATTIVO;
            // il suo dettaglio non deve mostrare percentuali oltre il 100%.
            double frac = mx > 0 ? Math.min(1.0, v / mx) : 0;
            boolean self = mi != null && mi.factionId == f.getId();
            String bestName = mi == null ? "" : mi.name;
            out.add(new Component(k, label(k), valueText(k, v), frac, weight(k), valueText(k, mx), bestName, self));
        }
        return out;
    }

    /** Punteggio della fazione = somma dei contributi (peso × frazione) delle caratteristiche attive. */
    public double score(Faction f) {
        return score(f, computeMaxes());
    }

    private double score(Faction f, Map<String, MaxInfo> maxes) {
        double s = 0;
        for (Component c : breakdown(f, maxes)) s += c.contribution();
        return s;
    }

    /** Punteggio formattato per la visualizzazione (decimali da {@code score.decimals}). */
    public String formatScore(double v) {
        return String.format(Locale.ITALY, "%,." + decimals() + "f", v);
    }

    /** Una riga del dettaglio: una caratteristica, col valore grezzo, la frazione rispetto al migliore
     *  (0-1) e il peso. I punti dati = peso × frazione (quindi al massimo = il peso). */
    public static final class Component {
        public final String key, label, valueText;
        public final double fraction;   // 0..1 (1 = sei tu il migliore in questa voce)
        public final double weight;     // massimo che la voce puo' dare
        public final String bestValueText;  // valore del migliore (il 100%)
        public final String bestName;       // fazione che detiene il migliore (il riferimento)
        public final boolean bestSelf;      // il migliore sei TU
        Component(String key, String label, String valueText, double fraction, double weight,
                  String bestValueText, String bestName, boolean bestSelf) {
            this.key = key; this.label = label; this.valueText = valueText; this.fraction = fraction; this.weight = weight;
            this.bestValueText = bestValueText; this.bestName = bestName; this.bestSelf = bestSelf;
        }
        /** Punti dati da questa voce = peso × frazione. */
        public double contribution() { return weight * fraction; }
        /** Percentuale rispetto al migliore ("76%"). */
        public String pctStr() { return Math.round(fraction * 100) + "%"; }
        /** Punti dati da questa voce, a due decimali. */
        public String pointsStr() { return String.format(Locale.ITALY, "%.2f", contribution()); }
        /** Il massimo della voce (= il peso), come testo pulito (senza decimali inutili). */
        public String maxStr() { return trimNum(weight); }
    }

    /** Numero senza decimali inutili: 1.0 -> "1", 1.5 -> "1,5". */
    private static String trimNum(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.ITALY, "%.2f", v).replaceAll("0+$", "").replaceAll(",$", "");
    }

    /**
     * Il dettaglio come JSON compatto, SNAPSHOT salvato su {@code factions.score_detail} a ogni campione:
     * lo legge il SITO per il tooltip della classifica. Forma (pct = % rispetto al migliore, p = punti dati,
     * m = massimo della voce = peso):
     * {@code [{"l":"Territori","v":"13","pct":"76%","p":"0,76","m":"1"}, ...]}.
     */
    public String detailJson(Faction f) {
        return detailJsonOf(breakdown(f));
    }

    /** Come {@link #detailJson(Faction)} ma su un dettaglio GIA' calcolato (evita di ricalcolarlo). */
    public String detailJsonOf(List<Component> parts) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Component c : parts) {
            if (!first) sb.append(","); first = false;
            sb.append("{\"l\":\"").append(escapeJson(c.label)).append("\",\"v\":\"").append(escapeJson(c.valueText))
              .append("\",\"pct\":\"").append(c.pctStr()).append("\",\"p\":\"").append(escapeJson(c.pointsStr()))
              .append("\",\"m\":\"").append(escapeJson(c.maxStr()))
              .append("\",\"bv\":\"").append(escapeJson(c.bestValueText))
              .append("\",\"bn\":\"").append(escapeJson(c.bestName))
              .append("\",\"self\":").append(c.bestSelf ? "true" : "false").append("}");
        }
        return sb.append("]").toString();
    }

    private static String escapeJson(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    // ------------------------------ CLASSIFICA -------------------------

    /** Voce di classifica: la fazione e il suo punteggio, gia' calcolato (evita di ricalcolarlo nel sort). */
    public static final class Entry {
        public final Faction faction;
        public final double score;
        Entry(Faction faction, double score) { this.faction = faction; this.score = score; }
    }

    /** Le fazioni ATTIVE ordinate per punteggio decrescente (a parita', per nome). Le inattive (oscurate)
     *  non compaiono in classifica. */
    public List<Entry> ranking() {
        Map<String, MaxInfo> maxes = computeMaxes();   // una volta sola per tutta la classifica
        List<Entry> list = new ArrayList<>();
        for (Faction f : fm.all()) if (isActive(f)) list.add(new Entry(f, score(f, maxes)));
        list.sort(Comparator.comparingDouble((Entry e) -> e.score).reversed()
                .thenComparing(e -> e.faction.getName(), String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    /** Posizione 1-based di una fazione nella classifica, o 0 se non c'e'. */
    public int position(Faction f) {
        List<Entry> r = ranking();
        for (int i = 0; i < r.size(); i++) if (r.get(i).faction.getId() == f.getId()) return i + 1;
        return 0;
    }

    // ------------------------------ CAMPIONE ---------------------------

    /**
     * Aggiorna gli integrali delle medie (banca, potenza) di TUTTE le fazioni fino a ORA, poi ricalcola e
     * salva punteggio + dettaglio (che dipendono dai massimi di TUTTE le fazioni, quindi in un secondo
     * giro dopo aver aggiornato tutti). Lo chiama il task periodico e onDisable. Solo main thread.
     */
    public void sampleAll() {
        // 1) chiudi l'intervallo aperto di ogni fazione (potenza sempre, banca solo se online) — cosi' le
        // medie sono al passo prima di calcolare i massimi. E' lo stesso flush usato ai cambi di stato.
        for (Faction f : fm.all()) flush(f);
        // 2) massimi correnti + snapshot di punteggio e dettaglio per il sito
        Map<String, MaxInfo> maxes = computeMaxes();
        for (Faction f : fm.all()) {
            List<Component> parts = breakdown(f, maxes);
            double sc = 0; for (Component c : parts) sc += c.contribution();
            f.setScore(sc);
            f.setScoreDetail(detailJsonOf(parts));
            f.setRanked(isActive(f));   // fazione inattiva -> oscurata dalla classifica (sito)
            fm.saveScoreSample(f);
        }
    }
}
