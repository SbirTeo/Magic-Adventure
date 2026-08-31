package com.teolo.magixfactions.model;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Appartenenza di un giocatore a una fazione. */
public final class Member {
    private final UUID uuid;
    private String rankId;          // id del grado, oppure Rank.LEADER_ID
    private long rankSince;         // da quando ha questo grado (per la successione)
    private long joinedAt;
    /** Override di permessi specifici del singolo membro (gestibili via GUI in futuro). */
    private final Map<String, Boolean> permissionOverrides = new HashMap<>();

    public Member(UUID uuid, String rankId, long rankSince, long joinedAt) {
        this.uuid = uuid;
        this.rankId = rankId;
        this.rankSince = rankSince;
        this.joinedAt = joinedAt;
    }

    public UUID getUuid() { return uuid; }
    public String getRankId() { return rankId; }
    public long getRankSince() { return rankSince; }
    public long getJoinedAt() { return joinedAt; }
    public Map<String, Boolean> getPermissionOverrides() { return permissionOverrides; }

    public boolean isLeader() { return Rank.LEADER_ID.equals(rankId); }

    public void setRank(String rankId, long since) {
        this.rankId = rankId;
        this.rankSince = since;
    }
}
