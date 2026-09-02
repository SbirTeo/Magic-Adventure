package com.teolo.magixguard.sanctions;

import java.util.UUID;

/**
 * Un provvedimento, come sta scritto nella tabella `sanzioni` del sito.
 *
 * @param id         numero del provvedimento (0 finche' non e' stato scritto)
 * @param uuid       il giocatore
 * @param nome       il suo nickname al momento del fatto
 * @param tipo       warn, mute, kick o ban
 * @param categoria  il codice di categoria (chat.spam, cheat.xray, manuale...)
 * @param motivo     la riga che legge il giocatore e che compare nell'elenco pubblico
 * @param ambito     dove vale
 * @param punti      punti accreditati al registro
 * @param inizio     quando comincia
 * @param fine       quando finisce; {@link Duration#PERMANENTE} = mai
 * @param staff      chi l'ha deciso, oppure null se e' stato il plugin
 * @param automatica true se non c'e' stata una persona a deciderla
 * @param impronta   SHA-256 del rapporto firmato, se c'e'
 */
public record Sanction(int id, UUID uuid, String name, Type type, String category, String reason,
                       Scope scope, int points, long beginning, long fine, String staff,
                       boolean automaticMode, String impronta) {

    /** Il provvedimento e' ancora in corso adesso? */
    public boolean activate() {
        return fine == Duration.PERMANENTE || fine > System.currentTimeMillis();
    }

    /** Duration complessiva, per come la si racconta. */
    public String readableDuration() {
        if (!type.hasDuration()) {
            return "immediata";
        }
        return fine == Duration.PERMANENTE ? "permanente" : Duration.write(fine - beginning);
    }

    /** Chi l'ha decisa, detto a un umano. */
    public String autore() {
        if (staff != null && !staff.isBlank()) {
            return staff;
        }
        return automaticMode ? "Sistema automatico" : "Staff";
    }

    /** La stessa sanzione, con il numero assegnato dal database. */
    public Sanction conId(int nuovoId) {
        return new Sanction(nuovoId, uuid, name, type, category, reason, scope, points,
                beginning, fine, staff, automaticMode, impronta);
    }
}
