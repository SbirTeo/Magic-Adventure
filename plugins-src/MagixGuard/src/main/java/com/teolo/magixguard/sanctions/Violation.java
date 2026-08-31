package com.teolo.magixguard.sanctions;

import java.util.UUID;

/**
 * Un fatto accertato: un messaggio bloccato, una violazione dell'anticheat, uno scavo che non
 * torna. Non e' un provvedimento — e' quello che <b>porta punti</b>, e sono i punti a far
 * scattare il provvedimento quando si accumulano.
 *
 * <p>La distinzione conta: la maggior parte delle violazioni non produce nessuna sanzione, e
 * deve restare comunque registrata. Senza, il terzo spam di un giocatore sarebbe identico al
 * primo, e non lo e'.</p>
 *
 * @param id        numero della violazione (0 finche' non e' scritta)
 * @param uuid      il giocatore
 * @param nome      il suo nickname in quel momento
 * @param categoria il codice della categoria in sanzioni.yml
 * @param punti     quanto vale, prima del decadimento
 * @param fonte     chi l'ha vista: chat, xray, afk, grim, report
 * @param dettaglio le prove, gia' leggibili da una persona
 * @param quando    quando e' successa
 */
public record Violation(int id, UUID uuid, String nome, String categoria, int punti,
                         String fonte, String dettaglio, long quando) {

    public static Violation nuova(UUID uuid, String nome, String categoria, int punti,
                                   String fonte, String dettaglio) {
        return new Violation(0, uuid, nome, categoria, punti, fonte, dettaglio,
                System.currentTimeMillis());
    }
}
