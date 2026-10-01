package com.teolo.magixessentials.currency;

/**
 * L'esito di un {@link CurrencyStore#give}: {@code success} false vuol dire saldo insufficiente
 * (niente e' stato toccato), e in quel caso i due saldi sono quelli di PRIMA, per il messaggio
 * di errore ("ne hai {balance}, te ne servono {amount}").
 */
record GiveOutcome(boolean success, long senderBalance, long targetBalance) {
}
