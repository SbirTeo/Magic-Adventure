package com.teolo.magixguard.analyze;

/**
 * I tipi di indizio che il plugin sa riconoscere.
 *
 * Ogni tipo porta con se' due cose: la chiave con cui si configura il peso (config.yml,
 * sezione analysis.weights / analysis.exculpatory) e la frase in italiano che finisce
 * nel dossier. La descrizione e' pensata per essere letta da un giocatore che fa ricorso,
 * non da uno sviluppatore.
 */
public enum EvidenceType {

    SAME_COOKIE("same-cookie", false,
            "Stessa installazione di gioco (token client identico)"),
    SAME_IP_ACTIVE("same-ip-active", false,
            "Stesso indirizzo IP nello stesso periodo"),
    SAME_IP_HISTORY("same-ip-history", false,
            "Stesso indirizzo IP in momenti diversi"),
    SAME_SUBNET24("same-subnet24", false,
            "Stessa sottorete dell'operatore (/24)"),
    SAME_RDNS("same-rdns", false,
            "Stesso nome di rete inverso: stessa linea internet"),
    SAME_FINGERPRINT("same-fingerprint", false,
            "Impronta del client identica (brand, mod, opzioni di gioco)"),
    SAME_CHANNELS("same-channels", false,
            "Identica lista di mod dichiarate dal client"),
    HANDOFF("handoff", false,
            "Staffetta: un account esce e l'altro entra subito dopo dallo stesso IP"),
    REGISTERED_CLOSE("registered-close", false,
            "Primo accesso al server ravvicinato, dallo stesso IP"),
    SIMILAR_NAME("similar-name", false,
            "Nickname costruiti sullo stesso schema"),
    NEVER_TOGETHER("never-together", false,
            "Non sono mai stati online nello stesso momento"),

    // --- indizi a DISCOLPA: pesi negativi, tengono fuori fratelli e coinquilini ---
    ONLINE_TOGETHER("online-together", true,
            "Visti online insieme: sono stati collegati nello stesso momento"),
    DIFFERENT_FINGERPRINT("different-fingerprint", true,
            "Client nettamente diversi fra i due account");

    private final String configKey;
    private final boolean exculpatory;
    private final String description;

    EvidenceType(String configKey, boolean exculpatory, String description) {
        this.configKey = configKey;
        this.exculpatory = exculpatory;
        this.description = description;
    }

    public String configKey() { return configKey; }

    /** true = prova a favore del giocatore (peso negativo). */
    public boolean exculpatory() { return exculpatory; }

    public String description() { return description; }

    public static EvidenceType parse(String s) {
        for (EvidenceType t : values()) if (t.name().equalsIgnoreCase(s)) return t;
        return null;
    }
}
