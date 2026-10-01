package com.teolo.magixessentials.currency;

/**
 * Una valuta configurata in {@code currencies.yml}: l'id e' anche il nome del comando che
 * {@link CurrencyManager} registra ({@code /<id>}) e il pezzo centrale dei suoi permessi
 * ({@code magixessentials.currency.<id>.admin}, {@code .give}).
 *
 * @param id               l'id scelto dallo staff (lettere minuscole, cifre, trattino basso)
 * @param name             il nome mostrato nei messaggi ai giocatori (es. "Gemme")
 * @param startingBalance  il saldo di un giocatore che questa valuta non l'ha mai vista
 * @param shared           true = saldo condiviso su tutta la rete (database), false = locale a
 *                         questo server (file) — il server sta dietro Velocity con piu' backend
 */
public record Currency(String id, String name, long startingBalance, boolean shared) {
}
