package com.teolo.magixfactions.model;

import java.util.HashSet;
import java.util.Set;

/** Un grado della fazione, definito nel config. Il leader e' un grado speciale. */
public final class Rank {
    public static final String LEADER_ID = "leader";

    private final String id;
    private final String name;
    private final String tag;
    private final Set<String> permissions;
    private final boolean leader;

    public Rank(String id, String name, String tag, Set<String> permissions, boolean leader) {
        this.id = id;
        this.name = name;
        this.tag = tag;
        this.permissions = new HashSet<>(permissions);
        this.leader = leader;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getTag() { return tag; }
    public boolean isLeader() { return leader; }

    public boolean has(String node) {
        return leader || permissions.contains("*") || permissions.contains(node);
    }
}
