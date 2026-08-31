package com.teolo.magixfactions.model;

/**
 * Relazione fra due fazioni. Esistono solo due stati: ENEMY e ALLY.
 * ENEMY e' il DEFAULT (ogni fazione e' nemica di tutte le altre) e non viene salvato nel DB:
 * nel database si memorizzano solo le alleanze.
 */
public enum RelationType {
    ENEMY, ALLY;

    public static RelationType parse(String s) {
        if (s == null) return null;
        switch (s.toLowerCase()) {
            case "ally": case "alleato": case "alleati": case "a": return ALLY;
            case "enemy": case "nemico": case "nemici": case "e": return ENEMY;
            default: return null;
        }
    }
}
