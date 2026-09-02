package com.teolo.magixauth.model;

/**
 * A che punto e' un giocatore fermo al cancello.
 *
 * L'ordine e' quello, e non e' arbitrario: prima la password, poi il codice. Chiedere il
 * codice per primo permetterebbe a chiunque conosca il nick di un amministratore di
 * sbagliarlo di proposito finche' il conto dei tentativi non lo blocca — chiudendolo fuori
 * dal suo stesso server senza aver mai saputo nulla della sua password.
 */
public enum Phase {

    /** Nome mai visto: deve scegliersi una password. */
    REGISTRAZIONE,

    /** Account gia' esistente: deve digitare la sua password. */
    PASSWORD,

    /** Password superata, ma questo account (o questo dispositivo) deve il secondo fattore. */
    OTP,

    /** Dentro: da qui in poi e' un giocatore come gli altri. */
    LIBERO
}
