package com.teolo.magixguard.model;

import java.util.UUID;

/** Riferimento minimo a un account profilato. */
public record PlayerRef(long id, UUID uuid, String name, long firstSeen, long lastSeen,
                        int sessionCount, long playtimeSeconds) {

    public PlayerRef(long id, UUID uuid, String name) {
        this(id, uuid, name, 0L, 0L, 0, 0L);
    }
}
