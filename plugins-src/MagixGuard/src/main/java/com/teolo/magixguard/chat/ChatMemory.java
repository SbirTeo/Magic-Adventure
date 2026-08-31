package com.teolo.magixguard.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le ultime righe di chat, tenute in memoria per due motivi diversi.
 *
 * <p><b>Per riconoscere lo spam:</b> serve sapere quanti messaggi ha scritto quel giocatore negli
 * ultimi secondi e cosa aveva scritto prima.</p>
 *
 * <p><b>Per le prove:</b> un insulto isolato non dimostra niente. Quello che rende utilizzabile
 * una riga di chat sono le righe intorno — chi aveva detto cosa un attimo prima. Senza contesto
 * il rapporto e' un'accusa a memoria, e in un ricorso non regge.</p>
 *
 * <p>Vive solo in memoria e sparisce al riavvio: quello che conta davvero viene copiato nel
 * dettaglio della violazione nel momento in cui succede.</p>
 */
public final class ChatMemory {

    /** Una riga di chat come la ricordiamo. */
    public record Riga(String autore, String messaggio, long quando) { }

    private final int righeContesto;
    private final Deque<Riga> globale = new ArrayDeque<>();
    private final Map<UUID, Deque<Riga>> perGiocatore = new ConcurrentHashMap<>();

    public ChatMemory(int righeContesto) {
        this.righeContesto = Math.max(2, righeContesto);
    }

    /** Registra una riga appena scritta. */
    public synchronized void aggiungi(UUID uuid, String autore, String messaggio) {
        Riga r = new Riga(autore, messaggio, System.currentTimeMillis());
        globale.addLast(r);
        while (globale.size() > righeContesto * 4) {
            globale.removeFirst();
        }
        Deque<Riga> sue = perGiocatore.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (sue) {
            sue.addLast(r);
            while (sue.size() > 12) {
                sue.removeFirst();
            }
        }
    }

    /** Quanti messaggi ha scritto quel giocatore negli ultimi N millisecondi. */
    public int quantiNegliUltimi(UUID uuid, long millis) {
        Deque<Riga> sue = perGiocatore.get(uuid);
        if (sue == null) {
            return 0;
        }
        long da = System.currentTimeMillis() - millis;
        int conta = 0;
        synchronized (sue) {
            for (Riga r : sue) {
                if (r.quando() >= da) {
                    conta++;
                }
            }
        }
        return conta;
    }

    /** L'ultimo messaggio di quel giocatore, o null. */
    public String ultimoDi(UUID uuid) {
        Deque<Riga> sue = perGiocatore.get(uuid);
        if (sue == null) {
            return null;
        }
        synchronized (sue) {
            return sue.isEmpty() ? null : sue.peekLast().messaggio();
        }
    }

    /**
     * Il contesto da allegare a una violazione: le ultime righe della chat pubblica, in ordine,
     * con l'ora. E' quello che una persona leggera' fra sei mesi per decidere un ricorso.
     */
    public synchronized String contesto() {
        if (globale.isEmpty()) {
            return "";
        }
        List<Riga> ultime = new ArrayList<>(globale);
        int da = Math.max(0, ultime.size() - righeContesto);
        StringBuilder b = new StringBuilder("Contesto della chat (dal piu' vecchio):\n");
        for (int i = da; i < ultime.size(); i++) {
            Riga r = ultime.get(i);
            b.append("  [").append(com.teolo.magixguard.util.Fmt.shortDateTime(r.quando()))
             .append("] ").append(r.autore()).append(": ").append(r.messaggio()).append('\n');
        }
        return b.toString();
    }

    /** Un giocatore se ne va: la sua finestra non serve piu'. */
    public void dimentica(UUID uuid) {
        perGiocatore.remove(uuid);
    }
}
