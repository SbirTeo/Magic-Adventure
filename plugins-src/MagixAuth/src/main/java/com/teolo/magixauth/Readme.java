package com.teolo.magixauth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Riscrive il README nella cartella del plugin a ogni avvio.
 *
 * E' la convenzione di questo server: chi apre `plugins/MagixAuth/` deve trovarci una
 * spiegazione aggiornata di cosa fa il plugin e come e' configurato adesso, senza dover
 * cercare altrove. Rigenerandolo a ogni avvio non puo' restare indietro rispetto al codice.
 */
final class Readme {

    private Readme() {
    }

    static void rigenera(MagixAuth plugin, AuthConfig config) {
        String text = """
                # MagixAuth

                Registrazione e login per il server non premium.

                Aggiornato automaticamente all'avvio del %s.

                ## L'idea in una riga

                L'account di gioco **e' lo stesso** account del sito: la password sta in
                `users.password_hash` di magicadventure.it. Chi si registra in gioco puo'
                entrare subito su `magicadventure.it` con lo stesso nome, e chi aveva gia'
                impostato la password dal sito e' gia' registrato in gioco.

                ## Come entra un giocatore

                1. **Prima di comparire** gli viene assegnato il suo UUID di sempre. Non e'
                   calcolato dal nome: e' quello salvato nel suo account, compreso l'UUID
                   premium di quando il server era in online mode. E' cio' che ha permesso
                   di passare a offline mode senza migrare permessi, fazioni e op.
                2. **Compare al punto di spawn**, non dove si trovava, e guarda nella
                   direzione dello spawn del mondo. Invisibile agli altri,
                   con gli altri invisibili a lui, chat esclusa. Serve a due cose: chi entra
                   col nome di un altro non ne legge le coordinate, e i chunk di casa sua non
                   vengono caricati finche' non ha superato il login.
                3. **Scrive la password su un cartello.** Non in chat: li' finirebbe nei
                   registri del server e nel ponte con la chat del sito.
                4. **Se serve, digita il codice** sul tastierino: nove tasti da 1 a 9 piu' la
                   riga con lo zero, cancella e conferma.
                5. **Torna dov'era**, ricompare agli altri, e solo adesso il server annuncia
                   che e' arrivato.

                ## Chi deve la verifica in due passaggi

                | Chi | Obbligatoria |
                |---|---|
                | Web-admin del sito (`users.is_admin`) | si' |
                | Staff, cioe' la track LuckPerms `%s` | si' |
                | Tutti gli altri | solo se l'hanno attivata dal sito |

                ## Come e' configurato adesso

                - Tempo per completare l'ingresso: **%d secondi**
                - Tentativi prima del blocco: **%d**, poi **%d minuti** di attesa
                - Lunghezza minima della password: **%d caratteri**
                - Un dispositivo riconosciuto resta valido **%d ore**
                - Annotazione degli account premium: **%s**
                - Skin presa da Mojang: **%s**, richiesta di nuovo ogni **%d minuti**

                Il dispositivo si riconosce in due modi, e basta che ne funzioni uno: un
                gettone depositato sul client (sparisce quando chiude il gioco) e l'indirizzo di
                rete (cambia da solo sulle connessioni mobili, e in una stessa casa e' condiviso
                da tutti). Ognuno dei due copre il buco dell'altro; presi da soli, entrambi
                rimandano alla password piu' spesso del dovuto.

                ## Comandi

                - `/changepassword` — cambia la propria password (vale anche sul sito)
                - `/mauth info` — stato del plugin
                - `/mauth reset <giocatore>` — azzera la password di chi l'ha dimenticata
                - `/mauth unlock <giocatore|indirizzo>` — toglie l'attesa a chi ha sbagliato troppe volte
                - `/mauth sessions <giocatore>` — dimentica i suoi dispositivi
                - `/mauth reload` — rilegge la configurazione

                ## Chiuso fuori per troppi tentativi

                Dopo **%d** password sbagliate l'indirizzo resta fermo **%d minuti**, e chi
                ci prova viene respinto al cancello con scritto quanto manca. Il conto sta
                sull'INDIRIZZO e non sul nome di proposito: contarlo sul nome vorrebbe dire
                che chiunque, sbagliando apposta la password di un altro, puo' chiuderlo fuori
                sapendone soltanto il nick. Il rovescio e' che in una casa sola, o dietro la
                stessa rete, il blocco lo prendono tutti.

                Chi non vuole aspettare lo si libera con `/mauth unlock <giocatore>`: cerca
                gli indirizzi bloccati partendo dal nome — l'ultimo provato su quel blocco, e
                quelli da cui il giocatore era gia' entrato — e toglie anche l'eventuale blocco
                del codice in due passaggi, che invece e' per account. Se il blocco non porta
                nessun nome riconoscibile, si passa direttamente l'indirizzo.

                ## Cose da sapere

                - Il plugin va caricato **all'avvio del server**, mai a caldo: chi e' gia'
                  collegato non passerebbe dal cancello.
                - Se il database non risponde all'avvio, il plugin **non parte**. E' voluto:
                  un cancello che non sa chi sia nessuno e' peggio di nessun cancello.
                - `database.otp_key_base64` e' la `OTP_CHIAVE` di `website/config.php`.
                  Senza, nessun codice di verifica puo' essere controllato.
                - Con `online-mode=false` serve anche `enforce-secure-profile=false`.
                - La skin la mette il server: in offline mode il gioco non la chiede piu' a
                  nessuno. Se la richiesta a Mojang fallisce, il giocatore entra con la faccia
                  di serie — o, da un launcher non ufficiale, con quella di uno sconosciuto che
                  ha registrato quel nickname sul servizio di skin del launcher. Il fallimento
                  finisce nel log e viene ritentato al primo ingresso successivo.

                ## Non ancora fatto

                - **Auto-login dei giocatori premium.** Richiede di scrivere a mano la fase
                  di crittografia del protocollo di login, ed e' rimandato: si rivaluta
                  quando arrivera' Velocity, dove la stessa cosa e' molto piu' pulita.
                  Nel frattempo gli account premium vengono **annotati** (`users.premium_uuid`)
                  senza usarne l'UUID, cosi' quel giorno si sapra' gia' chi migrare.
                """.formatted(
                LocalDate.now(),
                config.staffTrack,
                config.maxSeconds,
                config.maxAttempts,
                config.lockoutMinutes,
                config.minPasswordLength,
                config.sessionHours,
                config.noteUuidPremium ? "attiva" : "spenta",
                config.skinFromMojang ? "attiva" : "spenta",
                config.skinCacheMinutes,
                // I due qui sotto sono della sezione "Chiuso fuori per troppi tentativi",
                // che nel testo viene dopo: l'ordine e' quello in cui compaiono, non quello
                // in cui li si legge nel config.
                config.maxAttempts,
                config.lockoutMinutes);

        try {
            Path folder = plugin.getDataFolder().toPath();
            Files.createDirectories(folder);
            Files.writeString(folder.resolve("README.md"), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("MagixAuth: README non aggiornato (" + e.getMessage() + ").");
        }
    }
}
