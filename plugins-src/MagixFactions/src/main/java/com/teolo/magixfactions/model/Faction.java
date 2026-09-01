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

    public double getBankAvgAccum() { return bankAvgAccum; }
    public void setBankAvgAccum(double v) { this.bankAvgAccum = v; }
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

    public Member getMember(UUID uuid) { return members.get(uuid); }
    public boolean isMember(UUID uuid) { return members.containsKey(uuid); }
    public int size() { return members.size(); }
}
