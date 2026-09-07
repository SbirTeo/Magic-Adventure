package com.teolo.magixfactions.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Una fazione con i suoi membri. */
public final class Faction {
    private final long id;
    private String name;
    private String tag;
    private String description = "";
    private UUID leader;
    private final long createdAt;
    private long memberLimitBonus;
    // Ultimo cambio nome (ms) per il cooldown di /f rename: 0 = mai rinominata (nessuna attesa).
    private long renamedAt;
    private final Map<UUID, Member> members = new HashMap<>();

    public Faction(long id, String name, String tag, UUID leader, long createdAt, long memberLimitBonus) {
        this.id = id;
        this.name = name;
        this.tag = tag;
        this.leader = leader;
        this.createdAt = createdAt;
        this.memberLimitBonus = memberLimitBonus;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getDescription() { return description == null ? "" : description; }
    public void setDescription(String description) { this.description = description == null ? "" : description; }
    public UUID getLeader() { return leader; }
    public void setLeader(UUID leader) { this.leader = leader; }
    public long getCreatedAt() { return createdAt; }
    public long getMemberLimitBonus() { return memberLimitBonus; }
    public void setMemberLimitBonus(long b) { this.memberLimitBonus = b; }
    public long getRenamedAt() { return renamedAt; }
    public void setRenamedAt(long v) { this.renamedAt = v; }

    // Banca di fazione: saldo comune alimentato da /f deposit, prelevabile con /f withdraw (permesso
    // di grado 'withdraw'). Persistito nella colonna factions.bank (vedi FactionManager.setBank).
    private double bank = 0;

    public double getBank() { return bank; }
    public void setBank(double bank) { this.bank = Math.max(0, bank); }
    public Map<UUID, Member> getMembers() { return members; }

    // ---- Medie nel tempo per il PUNTEGGIO fazione (vedi ScoreManager) --------------------------------
    // Il punteggio della classifica non usa il saldo/potenza ISTANTANEI ma la loro MEDIA nel tempo
    // ("giacenza media" della banca, "potenza media" della fazione): un milione comparso ieri e ritirato
    // domani non deve gonfiare la posizione. Si tiene un integrale (valore x secondi) che un campionatore
    // periodico incrementa, e la media = integrale / durata della finestra. Persistiti su factions.
    private double bankAvgAccum = 0;    // Σ(saldo × secondi) accumulato fino a scoreSampledAt
    private double powerAvgAccum = 0;   // Σ(potenza fazione × secondi) accumulato fino a scoreSampledAt
    private long scoreSampledAt = 0;    // ultimo istante (ms) in cui l'integrale è stato aggiornato
    private long scoreSince = 0;        // inizio della finestra di media (ms): creazione, o upgrade per le vecchie
    // Secondi con almeno un membro ONLINE: e' il denominatore della GIACENZA MEDIA della banca, che conta
    // solo il tempo in cui si gioca (a server vuoto il tempo si ferma per la banca). La potenza invece si
    // media sul tempo reale (scoreSince): deve calare anche da offline.
    private double bankActiveSeconds = 0;

    public double getBankAvgAccum() { return bankAvgAccum; }
    public void setBankAvgAccum(double v) { this.bankAvgAccum = v; }
    public double getBankActiveSeconds() { return bankActiveSeconds; }
    public void setBankActiveSeconds(double v) { this.bankActiveSeconds = v; }
    public double getPowerAvgAccum() { return powerAvgAccum; }
    public void setPowerAvgAccum(double v) { this.powerAvgAccum = v; }
    public long getScoreSampledAt() { return scoreSampledAt; }
    public void setScoreSampledAt(long v) { this.scoreSampledAt = v; }
    public long getScoreSince() { return scoreSince; }
    public void setScoreSince(long v) { this.scoreSince = v; }

    // Punteggio composito calcolato, SNAPSHOT persistito su factions.score a ogni campionamento: lo legge
    // il SITO per la classifica (evita di duplicare la formula e i pesi del config in PHP). Nel gioco il
    // punteggio si ricalcola sempre live (ScoreManager.score); questo e' solo la copia per l'esterno.
    private double score = 0;
    public double getScore() { return score; }
    public void setScore(double v) { this.score = v; }

    // Dettaglio del punteggio in JSON (una voce per caratteristica), SNAPSHOT persistito su
    // factions.score_detail: lo legge il sito per il tooltip "come si arriva a questo punteggio".
    private String scoreDetail = "[]";
    public String getScoreDetail() { return scoreDetail == null ? "[]" : scoreDetail; }
    public void setScoreDetail(String v) { this.scoreDetail = v; }

    // In classifica (true) o OSCURATA perche' inattiva (tutti i membri assenti da troppo): lo decide il
    // campionatore (ScoreManager) e lo legge il sito. Persistito su factions.ranked.
    private boolean ranked = true;
    public boolean isRanked() { return ranked; }
    public void setRanked(boolean v) { this.ranked = v; }

    public Member getMember(UUID uuid) { return members.get(uuid); }
    public boolean isMember(UUID uuid) { return members.containsKey(uuid); }
    public int size() { return members.size(); }
}
