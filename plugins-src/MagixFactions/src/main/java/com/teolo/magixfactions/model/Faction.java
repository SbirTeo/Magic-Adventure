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

    public Member getMember(UUID uuid) { return members.get(uuid); }
    public boolean isMember(UUID uuid) { return members.containsKey(uuid); }
    public int size() { return members.size(); }
}
