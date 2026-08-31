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
public record Sanction(int id, UUID uuid, String nome, Type tipo, String categoria, String motivo,
                       Scope ambito, int punti, long inizio, long fine, String staff,
                       boolean automatica, String impronta) {

    /** Il provvedimento e' ancora in corso adesso? */
    public boolean attiva() {
        return fine == Duration.PERMANENTE || fine > System.currentTimeMillis();
    }

    /** Duration complessiva, per come la si racconta. */
    public String durataLeggibile() {
        if (!tipo.haDurata()) {
            return "immediata";
        }
        return fine == Duration.PERMANENTE ? "permanente" : Duration.scrivi(fine - inizio);
    }

    /** Chi l'ha decisa, detto a un umano. */
    public String autore() {
        if (staff != null && !staff.isBlank()) {
            return staff;
        }
        return automatica ? "Sistema automatico" : "Staff";
    }

    /** La stessa sanzione, con il numero assegnato dal database. */
    public Sanction conId(int nuovoId) {
        return new Sanction(nuovoId, uuid, nome, tipo, categoria, motivo, ambito, punti,
                inizio, fine, staff, automatica, impronta);
    }
}
