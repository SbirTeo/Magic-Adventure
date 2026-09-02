package com.teolo.magixfactions.manage;

import com.teolo.magixfactions.model.Faction;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * PUNTEGGIO di fazione e CLASSIFICA (/f top).
 *
 * <h2>Perche' esiste</h2>
 * Una "Top fazioni" che ordina per un solo numero (territori, o membri, o soldi) e' fuorviante: premia
 * chi eccelle in una cosa sola. Qui il punteggio e' la SINTESI di piu' caratteristiche, ognuna PESATA
 * quanto decide l'amministratore nel config.
 *
 * <h2>Come si calcola</h2>
 * Due passaggi, per far si' che caratteristiche con scale diversissime (territori ~decine, soldi
 * ~centinaia di migliaia) siano confrontabili e i pesi esprimano una vera IMPORTANZA:
 * <ol>
 *   <li><b>LIVELLO 0-100</b> per ogni caratteristica, con un <i>tetto morbido a rendimenti decrescenti</i>:
 *       {@code livello = 100 · v / (v + K)}. K (il "riferimento") e' il valore a cui la caratteristica
 *       vale 50. La curva CRESCE sempre (piu' e' sempre un po' di piu': niente pareggi in cima) ma non
 *       arriva mai a 100 (i numeri giganti non "schizzano": un milione in banca resta sotto il tetto).</li>
 *   <li><b>PESO</b>: il punteggio finale e' la media dei livelli pesata coi pesi del config
 *       ({@code score.weights.*}), normalizzata sulla somma dei pesi attivi -> sempre in 0-100. Cosi'
 *       "territori peso 30, banca peso 15" significa che i territori contano il DOPPIO dei soldi,
 *       indipendentemente dai numeri grezzi.</li>
 * </ol>
 *
 * <h2>Medie nel tempo</h2>
 * Banca e Potenza NON entrano col valore istantaneo ma con la loro MEDIA nel tempo dalla creazione
 * ("giacenza media", "potenza media"): un picco momentaneo non gonfia la classifica. La media si tiene
 * con un integrale {@code valore × secondi} accumulato sulla fazione (colonne {@code factions.*_avg_accum},
 * {@code score_sampled_at}, {@code score_since}) e aggiornato da un campionatore periodico; qui il valore
 * "aperto" dall'ultimo campione a ORA viene aggiunto al volo a ogni lettura, cosi' il punteggio e' sempre
 * attuale anche fra un campione e l'altro. Territori e Longevita' usano invece il valore corrente (numero
 * di chunk, giorni dalla creazione).
 */
public final class ScoreManager {

    private final JavaPlugin plugin;
    private final FactionManager fm;
    private final PowerManager power;
    private final ClaimManager claims;

    public ScoreManager(JavaPlugin plugin, FactionManager fm, PowerManager power, ClaimManager claims) {
        this.plugin = plugin;
        this.fm = fm;
        this.power = power;
        this.claims = claims;
    }

    // ----------------------------- CONFIG -----------------------------

    private double weight(String key, double def) {
        return Math.max(0, plugin.getConfig().getDouble("score.weights." + key, def));
    }

    private double reference(String key, double def) {
        return plugin.getConfig().getDouble("score.references." + key, def);
    }

    public int sampleIntervalSeconds() {
        return Math.max(5, plugin.getConfig().getInt("score.sample-interval-seconds", 300));
    }

    public int topSize() {
        return Math.max(1, plugin.getConfig().getInt("score.top-size", 10));
    }

    private int decimals() {
        return Math.max(0, Math.min(4, plugin.getConfig().getInt("score.decimals", 1)));
    }

    // --------------------------- LIVELLI 0-100 -------------------------

    /**
     * Livello 0-100 di una caratteristica col tetto morbido {@code 100·v/(v+K)}.
     * Valore negativo (puo' capitare alla Potenza) trattato come 0. K &le; 0 disattiva la caratteristica
     * (livello 0): e' il modo per escluderne una dal riferimento oltre che dal peso.
     */
    public static double level(double v, double k) {
        if (v <= 0 || k <= 0) return 0;
        return 100.0 * v / (v + k);
    }

    // --------------------------- MEDIE NEL TEMPO -----------------------

    private double openBankAccum(Faction f, long now) {
        double dtSec = Math.max(0, now - f.getScoreSampledAt()) / 1000.0;
        return f.getBankAvgAccum() + f.getBank() * dtSec;
    }

    private double openPowerAccum(Faction f, long now) {
        double dtSec = Math.max(0, now - f.getScoreSampledAt()) / 1000.0;
        return f.getPowerAvgAccum() + power.factionPower(f) * dtSec;
    }

    /** Giacenza MEDIA della banca dalla creazione (o dall'upgrade, per le fazioni piu' vecchie). */
    public double averageBank(Faction f) {
        long now = System.currentTimeMillis();
        double windowSec = (now - f.getScoreSince()) / 1000.0;
        if (windowSec < sampleIntervalSeconds()) return f.getBank();   // troppo poco storico: valore attuale
        return openBankAccum(f, now) / windowSec;
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

    // ------------------------------ PUNTEGGIO --------------------------

    /** Punteggio composito 0-100 della fazione: somma dei contributi delle caratteristiche (=media dei
     *  livelli pesata coi pesi del config). Vedi {@link #breakdown(Faction)} per il dettaglio voce per voce. */
    public double score(Faction f) {
        double s = 0;
        for (Component c : breakdown(f)) s += c.contribution;
        return s;
    }

    /** Somma di TUTTI i pesi (le voci a peso 0 non contano): denominatore della media pesata. */
    private double totalWeight() {
        return weight("land", 30) + weight("members", 20) + weight("bank", 15)
                + weight("longevity", 15) + weight("power", 20);
    }

    /**
     * Il dettaglio del punteggio, una voce per caratteristica: valore usato, livello 0-100, quota di peso
     * e contributo al totale. Serve al TOOLTIP che spiega "come si arriva a quel punteggio" (in gioco al
     * passaggio del mouse in chat, sul sito nella classifica). La somma dei contributi = {@link #score}.
     */
    public List<Component> breakdown(Faction f) {
        List<Component> out = new ArrayList<>();
        double total = totalWeight();
        if (total <= 0) return out;
        int claimN = claims.count(f.getId());
        add(out, "land", "Territori", claimN, reference("land", 40), weight("land", 30), total, intText(claimN));
        add(out, "members", "Membri", f.size(), reference("members", 8), weight("members", 20), total, intText(f.size()));
        double avgBank = averageBank(f);
        add(out, "bank", "Banca (media)", avgBank, reference("bank", 500_000), weight("bank", 15), total, moneyText(avgBank));
        double days = ageDays(f);
        add(out, "longevity", "Longevità", days, reference("longevity", 90), weight("longevity", 15), total, daysText(days));
        double avgPow = averagePower(f);
        add(out, "power", "Potenza (media)", avgPow, reference("power", 200), weight("power", 20), total, intText(avgPow));
        return out;
    }

    private void add(List<Component> out, String key, String label, double v, double k, double w, double total, String valueText) {
        if (w <= 0) return;   // caratteristica esclusa (peso 0): non compare nel dettaglio
        double lvl = level(v, k);
        double share = w / total;
        out.add(new Component(key, label, valueText, lvl, share, share * lvl));
    }

    private static String intText(double v) { return String.valueOf(Math.round(v)); }
    private static String moneyText(double v) { return String.format(Locale.ITALY, "%,.0f", v); }
    private static String daysText(double v) { return Math.round(v) + "g"; }

    /** Una riga del dettaglio del punteggio (una caratteristica). I testi sono già formattati (locale IT),
     *  così gioco e sito mostrano gli stessi valori senza riformattare ognuno per conto suo. */
    public static final class Component {
        public final String key, label, valueText;
        public final double level, weightShare, contribution;
        Component(String key, String label, String valueText, double level, double weightShare, double contribution) {
            this.key = key; this.label = label; this.valueText = valueText;
            this.level = level; this.weightShare = weightShare; this.contribution = contribution;
        }
        public String levelStr() { return String.valueOf(Math.round(level)); }
        public String pctStr() { return Math.round(weightShare * 100) + "%"; }
        public String pointsStr() { return String.format(Locale.ITALY, "%.1f", contribution); }
    }

    /**
     * Il dettaglio come JSON compatto, SNAPSHOT salvato su {@code factions.score_detail} a ogni campione:
     * lo legge il SITO per il tooltip della classifica (i testi sono già formattati, il sito li mostra e
     * basta). In gioco il tooltip si costruisce live da {@link #breakdown(Faction)}, quindi qui non serve.
     * Forma: {@code [{"l":"Territori","v":"40","lv":"50","w":"30%","p":"15,0"}, ...]}.
     */
    public String detailJson(Faction f) {
        return detailJsonOf(breakdown(f));
    }

    /** Come {@link #detailJson(Faction)} ma su un dettaglio GIÀ calcolato (evita di ricalcolarlo). */
    public String detailJsonOf(List<Component> parts) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Component c : parts) {
            if (!first) sb.append(","); first = false;
            sb.append("{\"l\":\"").append(escapeHtml(c.label)).append("\",\"v\":\"").append(escapeHtml(c.valueText))
              .append("\",\"lv\":\"").append(c.levelStr()).append("\",\"w\":\"").append(c.pctStr())
              .append("\",\"p\":\"").append(escapeHtml(c.pointsStr())).append("\"}");
        }
        return sb.append("]").toString();
    }

    private static String escapeHtml(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }

    /** Punteggio formattato per la visualizzazione (decimali da {@code score.decimals}). */
    public String formatScore(double v) {
        return String.format(Locale.ITALY, "%,." + decimals() + "f", v);
    }

    // ------------------------------ CLASSIFICA -------------------------

    /** Voce di classifica: la fazione e il suo punteggio, gia' calcolato (evita di ricalcolarlo nel sort). */
    public static final class Entry {
        public final Faction faction;
        public final double score;
        Entry(Faction faction, double score) { this.faction = faction; this.score = score; }
    }

    /** Tutte le fazioni ordinate per punteggio decrescente (a parita', per nome). */
    public List<Entry> ranking() {
        List<Entry> list = new ArrayList<>();
        for (Faction f : fm.all()) list.add(new Entry(f, score(f)));
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
     * Aggiorna gli integrali delle medie (banca, potenza) di TUTTE le fazioni fino a ORA e li salva su
     * DB. Lo chiama il task periodico (ogni {@link #sampleIntervalSeconds()}) e onDisable, cosi' un crash
     * perde al massimo l'ultimo intervallo. Solo main thread (legge la cache; il salvataggio e' async).
     */
    public void sampleAll() {
        long now = System.currentTimeMillis();
        for (Faction f : fm.all()) {
            double dtSec = Math.max(0, now - f.getScoreSampledAt()) / 1000.0;
            if (dtSec <= 0) continue;
            f.setBankAvgAccum(f.getBankAvgAccum() + f.getBank() * dtSec);
            f.setPowerAvgAccum(f.getPowerAvgAccum() + power.factionPower(f) * dtSec);
            f.setScoreSampledAt(now);
            // Snapshot del punteggio + dettaglio, per la classifica del sito (legge factions.score /
            // score_detail). Il dettaglio si costruisce una volta e vale sia per lo score sia per il JSON.
            List<Component> parts = breakdown(f);
            double sc = 0; for (Component c : parts) sc += c.contribution;
            f.setScore(sc);
            f.setScoreDetail(detailJsonOf(parts));
            fm.saveScoreSample(f);
        }
    }
}
