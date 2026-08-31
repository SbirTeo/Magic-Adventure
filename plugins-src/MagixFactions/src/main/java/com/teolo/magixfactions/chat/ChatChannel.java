package com.teolo.magixfactions.chat;

/** Canali di chat selezionabili con /f chat. */
public enum ChatChannel {
    PUBLIC, FACTION, ALLY;

    public static ChatChannel parse(String s) {
        if (s == null) return null;
        switch (s.toLowerCase()) {
            case "faction": case "fazione": case "f": return FACTION;
            case "ally": case "alleati": case "a": return ALLY;
            case "public": case "pubblica": case "p": return PUBLIC;
            default: return null;
        }
    }

    public ChatChannel next() {
        return switch (this) {
            case PUBLIC -> FACTION;
            case FACTION -> ALLY;
            case ALLY -> PUBLIC;
        };
    }
}
