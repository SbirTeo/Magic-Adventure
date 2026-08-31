package com.teolo.magixauth.gate;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.MagixAuth;
import com.teolo.magixauth.crypt.OtpCodici;
import com.teolo.magixauth.crypt.Password;
import com.teolo.magixauth.db.AuthDao;
import com.teolo.magixauth.model.Account;
import com.teolo.magixauth.model.Fase;
import com.teolo.magixauth.premium.MojangLookup;
import com.teolo.magixauth.util.Testi;
import com.teolo.magixauth.util.TestiDurate;
import io.papermc.paper.connection.PlayerLoginConnection;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Il cancello: decide chi puo' entrare, e tiene fermo chi non ha ancora finito.
 *
 * L'ordine delle cose e' questo, e ogni passo sta dove sta per un motivo:
 *
 *  1. PRE-LOGIN (fuori dal thread principale, quindi si puo' parlare col database): si
 *     guarda se il nome e' gia' registrato, gli si assegna il suo UUID di sempre, e si
 *     decide se il computer da cui arriva e' gia' conosciuto.
 *  2. SPAWN (fase di configurazione, prima che il mondo lo veda): si mette da parte dov'era
 *     davvero e lo si fa comparire allo spawn. I chunk di casa sua non vengono caricati.
 *  3. INGRESSO: sipario calato, cartello davanti, cronometro avviato.
 *  4. USCITA dal cancello: password giusta, eventuale codice, e allora — e solo allora —
 *     torna dov'era, ricompare agli altri e il server annuncia il suo arrivo.
 */
public final class AuthGate {

    private final MagixAuth plugin;
    private final AuthConfig config;
    private final AuthDao dao;
    private final PoliticaOtp politica;
    private final Visibilita visibilita;
    private final MojangLookup mojang;
    private final Biscotto biscotto;

    /** Chi e' fermo al cancello adesso. */
    private final Map<UUID, StatoIngresso> fermi = new ConcurrentHashMap<>();

    /** Deciso al pre-login, ritirato al momento dell'ingresso. */
    private final Map<UUID, StatoIngresso> decisioni = new ConcurrentHashMap<>();

    public AuthGate(MagixAuth plugin, AuthConfig config, AuthDao dao, PoliticaOtp politica,
                    Visibilita visibilita, MojangLookup mojang) {
        this.plugin = plugin;
        this.config = config;
        this.dao = dao;
        this.politica = politica;
        this.visibilita = visibilita;
        this.mojang = mojang;
        this.biscotto = new Biscotto(plugin, config.cookieAttesaMillis);
    }

    public StatoIngresso stato(Player p) {
        return fermi.get(p.getUniqueId());
    }

    public boolean fermo(Player p) {
        return fermi.containsKey(p.getUniqueId());
    }

    // =================================================================================
    // 1. Pre-login: si decide tutto qui, dove si puo' aspettare il database
    // =================================================================================

    /**
     * @return null se il giocatore puo' proseguire, altrimenti il motivo del rifiuto
     */
    public String decidi(UUID uuidProposto, String nome, String ip,
                         PlayerLoginConnection connessione, ProfiloSetter profilo) {
        try {
            java.time.LocalDateTime bloccato = dao.bloccatoFino(ip);
            if (bloccato != null) {
                return Testi.kickTroppiTentativi(TestiDurate.finoA(bloccato));
            }

            Account account = dao.perNome(nome);

            // L'UUID e' un dato dell'account, non una funzione del nome: chi ha gia' una
            // riga si riprende il suo di sempre, compreso quello premium di quando il
            // server era in online mode. E' cio' che evita ogni migrazione di ops.json,
            // LuckPerms, fazioni e permessi.
            UUID uuid = account != null ? account.uuid : AuthDao.uuidOffline(nome);

            // La skin va rimessa a mano: in offline mode il gioco non la chiede piu' a
            // nessuno, e senza questo si entra con la faccia di serie — o, da un launcher
            // non ufficiale, con quella di uno sconosciuto che ha registrato questo nickname
            // sul servizio di skin del launcher. Le si passa anche l'UUID dell'account:
            // quando e' un UUID Mojang, la skin si chiede direttamente con quello.
            String[] skin = null;
            if (config.skinDaMojang) {
                MojangLookup.Ritrovata ritrovata = mojang.skinDi(uuid, nome);
                skin = ritrovata.skin();
                if (ritrovata.fallita()) {
                    // Un buco di rete non deve diventare permanente ne' invisibile: non
                    // viene messo in cache, e almeno si sa perche' quel giocatore e' entrato
                    // senza la sua faccia.
                    plugin.getLogger().warning("MagixAuth: skin di " + nome
                            + " non recuperata (" + ritrovata.motivo() + "). Si riprova al prossimo ingresso.");
                }
            }
            profilo.applica(uuid.equals(uuidProposto) ? null : uuid, skin);

            if (Bukkit.getPlayer(uuid) != null) {
                return Testi.KICK_NOME_OCCUPATO;
            }

            // Il dispositivo si riconosce in DUE modi, e basta che ne funzioni uno.
            //
            // L'indirizzo di rete da solo non basta: su una connessione mobile cambia anche
            // piu' volte in un'ora, e chi usciva cinque minuti prima si ritrovava la password
            // da ridigitare come se fosse a un computer mai visto (successo davvero).
            // Il gettone da solo non basta neppure: vive nella memoria del client e sparisce
            // quando il giocatore chiude il gioco, quindi non riconoscerebbe mai chi rientra
            // il giorno dopo. Uno copre il buco dell'altro.
            String dispositivo = ip;
            String cookieDispositivo = config.cookieDispositivo
                    ? biscotto.dispositivoDi(connessione) : null;

            Fase fase;
            if (account == null || !account.registrato()) {
                fase = Fase.REGISTRAZIONE;
            } else {
                // Computer gia' conosciuto e sessione ancora buona: si salta la password.
                Boolean sessioneConOtp = cookieDispositivo == null
                        ? null : dao.sessione(uuid, cookieDispositivo);
                if (sessioneConOtp == null) {
                    sessioneConOtp = dao.sessione(uuid, dispositivo);
                }
                boolean serveOtp = politica.serve(account, uuid) && account.haOtp();

                if (sessioneConOtp == null) {
                    fase = Fase.PASSWORD;
                } else if (serveOtp && config.otpSegueLaSessione && !sessioneConOtp) {
                    fase = Fase.OTP;
                } else {
                    fase = Fase.LIBERO;
                }
            }

            StatoIngresso stato = new StatoIngresso(uuid, nome, ip, account, dispositivo,
                    cookieDispositivo, fase);
            decisioni.put(uuid, stato);
            return null;

        } catch (SQLException e) {
            plugin.getLogger().warning("MagixAuth: database non raggiungibile al pre-login di "
                    + nome + " (" + e.getMessage() + ").");
            // Non si puo' sapere chi sia: si tiene fuori. Preferibile chiudere fuori il
            // proprietario per qualche minuto che far entrare chiunque col suo nome.
            return Testi.KICK_DATABASE;
        }
    }

    /**
     * Questo computer da adesso e' conosciuto.
     *
     * Si scrivono DUE righe: una per l'indirizzo di rete e una per il gettone appena
     * consegnato al client. Sono due chiavi diverse per la stessa porta, e al rientro basta
     * che ne funzioni una — l'indirizzo se il giocatore ha riaperto il gioco, il gettone se
     * ha cambiato indirizzo senza chiuderlo.
     *
     * Il gettone vecchio viene buttato: e' la rotazione, l'unica cosa che impedisce a una
     * copia intercettata di valere per sempre.
     */
    private void ricorda(Player p, StatoIngresso stato, boolean otpOk) throws SQLException {
        dao.salvaSessione(stato.uuid, stato.dispositivo, stato.ip, config.oreSessione, otpOk);

        if (!config.cookieDispositivo) {
            return;
        }
        byte[] gettone = Biscotto.nuovoGettone();
        dao.salvaSessione(stato.uuid, Biscotto.dispositivo(gettone), stato.ip, config.oreSessione, otpOk);
        if (stato.cookieDispositivo != null) {
            dao.dimenticaDispositivo(stato.uuid, stato.cookieDispositivo);
        }
        sulMain(() -> biscotto.consegna(p, gettone));
    }

    /**
     * Il pre-login non puo' toccare il profilo da solo: glielo passa il listener.
     *
     * @param uuid l'UUID da imporre, oppure null se quello proposto va gia' bene
     * @param skin {valore, firma} delle texture, oppure null per lasciare quella di serie
     */
    public interface ProfiloSetter {
        void applica(UUID uuid, String[] skin);
    }

    // =================================================================================
    // 2. Spawn: dov'era davvero, e dove lo facciamo comparire
    // =================================================================================

    /**
     * @return la posizione a cui farlo comparire, o null per lasciarlo dov'era
     */
    public Location dirottaSpawn(UUID uuid, Location vera) {
        StatoIngresso stato = decisioni.get(uuid);
        if (stato == null || stato.fase == Fase.LIBERO || !config.spawnAlPostoDellaPosizione) {
            return null;
        }
        stato.posizioneVera = vera == null ? null : vera.clone();

        // La posizione va anche nel database, e non solo qui in memoria: se si disconnette
        // mentre e' fermo allo spawn, il server salverebbe lo spawn come sua ultima
        // posizione e quella vera sarebbe persa per sempre.
        if (stato.posizioneVera != null && stato.posizioneVera.getWorld() != null) {
            Location l = stato.posizioneVera;
            plugin.async(() -> {
                try {
                    dao.salvaPosizione(uuid, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                            l.getYaw(), l.getPitch());
                } catch (SQLException e) {
                    plugin.getLogger().warning("MagixAuth: posizione di " + stato.nome
                            + " non salvata (" + e.getMessage() + ").");
                }
            });
        }

        World mondo = vera != null && vera.getWorld() != null
                ? vera.getWorld() : Bukkit.getWorlds().get(0);

        // Al CENTRO del blocco, non sul suo spigolo: `getSpawnLocation` restituisce le
        // coordinate intere del blocco, e chi ci viene messo sopra si ritrova incastrato
        // fra quattro blocchi invece che in piedi su uno. Mezzo blocco su ciascun asse
        // rimette il giocatore dove starebbe naturalmente.
        Location dove = mondo.getSpawnLocation().clone();
        dove.setX(dove.getBlockX() + 0.5);
        dove.setZ(dove.getBlockZ() + 0.5);
        // Lo sguardo e' quello dello spawn, non quello che aveva prima di uscire: il
        // cancello e' un posto costruito apposta (cartelli, tastierino) e chi arriva deve
        // trovarselo davanti. `getSpawnLocation` porta con se' l'angolo del punto di spawn
        // (yaw e pitch, quelli che /setworldspawn ha memorizzato), quindi la direzione
        // giusta e' gia' nel clone: basta non sovrascriverla con quella del giocatore.
        return dove;
    }

    // =================================================================================
    // 3. Ingresso
    // =================================================================================

    public void accogli(Player p) {
        StatoIngresso stato = decisioni.remove(p.getUniqueId());
        if (stato == null) {
            // Non dovrebbe succedere: vuol dire che il pre-login non e' passato di qui.
            // Nel dubbio si tratta come non autenticato, mai come autenticato.
            stato = new StatoIngresso(p.getUniqueId(), p.getName(),
                    p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress(),
                    null, ipDi(p), null, Fase.REGISTRAZIONE);
        }

        if (stato.fase == Fase.LIBERO) {
            // Computer conosciuto, sessione valida: entra senza accorgersi di nulla.
            fermi.put(p.getUniqueId(), stato);
            libera(p, false);
            return;
        }

        fermi.put(p.getUniqueId(), stato);
        visibilita.nascondi(p);
        avviaCronometro(p, stato);

        // Il pacchetto parte subito, ma non lo si aspetta: al primo ingresso di un giocatore
        // nuovo non fara' in tempo, e il tastierino resta leggibile lo stesso.

        StatoIngresso finale = stato;
        // Un attimo di respiro: al join il client sta ancora ricevendo il mondo, e un
        // messaggio mandato troppo presto scorre via prima che si veda qualcosa.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && fermo(p)) {
                istruzioni(p, finale);
            }
        }, 10L);
    }

    /**
     * Dice al giocatore cosa deve scrivere.
     *
     * Si ripete ogni quindici secondi perche' la chat scorre: chi arriva mentre gli altri
     * parlano perderebbe l'unico messaggio che gli spiega come entrare, e resterebbe fermo
     * senza capire perche'.
     */
    private void istruzioni(Player p, StatoIngresso stato) {
        if (stato.aspettaCodice()) {
            p.sendMessage(Testi.c(config.prefisso, Testi.OTP_SERVE));
            p.sendMessage(Testi.c("&7Scrivi &f/otp <codice a sei cifre>"));
        } else if (stato.inRegistrazione()) {
            p.sendMessage(Testi.c(config.prefisso, Testi.BENVENUTO_NUOVO));
            p.sendMessage(Testi.c("&7Scrivi &f/register <password> <ripeti password>"));
        } else {
            p.sendMessage(Testi.c(config.prefisso, Testi.BENTORNATO));
            p.sendMessage(Testi.c("&7Scrivi &f/login <password>"));
        }
    }

    private void avviaCronometro(Player p, StatoIngresso stato) {
        stato.taskScadenza = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && fermo(p)) {
                p.kick(Testi.c(Testi.KICK_TEMPO_SCADUTO));
            }
        }, config.secondiMassimi * 20L).getTaskId();

        stato.taskCartello = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (p.isOnline() && fermo(p)) {
                istruzioni(p, stato);
            } else if (stato.taskCartello != -1) {
                Bukkit.getScheduler().cancelTask(stato.taskCartello);
            }
        }, 300L, 300L).getTaskId();
    }

    // =================================================================================
    // 4. Le prove
    // =================================================================================

    /**
     * La password scritta sul cartello.
     *
     * Il confronto bcrypt costa qualche centinaio di millisecondi: farlo sul thread
     * principale vorrebbe dire fermare tutto il server a ogni tentativo, quindi si sposta
     * di la' e si torna qui solo per parlare col giocatore.
     */
    public void provaPassword(Player p, String scritta) {
        StatoIngresso stato = stato(p);
        if (stato == null || stato.occupato) {
            return;
        }
        stato.occupato = true;
        plugin.async(() -> {
            try {
                verifica(p, stato, scritta);
            } finally {
                stato.occupato = false;
            }
        });
    }

    /** La registrazione, chiamata da /register quando le due password coincidono. */
    public void registra(Player p, String scelta) {
        StatoIngresso stato = stato(p);
        if (stato == null || stato.occupato) {
            return;
        }
        stato.occupato = true;
        plugin.async(() -> {
            try {
                registra(p, stato, scelta);
            } finally {
                stato.occupato = false;
            }
        });
    }

    private void registra(Player p, StatoIngresso stato, String scritta) {
        String no = Password.perche_no(scritta, stato.nome, config.passwordMinima);
        if (no != null) {
            riproponi(p, "&c" + no);
            return;
        }

        // Annotazione premium: interessa sapere se il nome appartiene a un account vero,
        // ma non deve impedire la registrazione se Mojang non risponde.
        UUID premium = config.annotaUuidPremium ? mojang.cerca(stato.nome) : null;

        try {
            int id = dao.registra(stato.uuid, stato.nome, Password.impronta(scritta), premium);
            if (id < 0) {
                sulMain(() -> p.kick(Testi.c(Testi.KICK_NOME_OCCUPATO)));
                return;
            }
            ricorda(p, stato, false);
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixAuth: registrazione di " + stato.nome
                    + " fallita (" + e.getMessage() + ").");
            sulMain(() -> p.kick(Testi.c(Testi.KICK_DATABASE)));
            return;
        }

        sulMain(() -> {
            p.sendMessage(Testi.c(Testi.REGISTRATO));
            libera(p, true);
        });
    }

    private void verifica(Player p, StatoIngresso stato, String scritta) {
        Account account = stato.account;
        boolean giusta = account != null && Password.corrisponde(scritta, account.passwordHash);

        if (!giusta) {
            try {
                int falliti = dao.registraFallimento(stato.ip, stato.nome,
                        config.tentativiMassimi, config.bloccoMinuti);
                if (falliti >= config.tentativiMassimi) {
                    // Il blocco parte adesso, quindi manca esattamente quanto dura.
                    String manca = TestiDurate.daSecondi(config.bloccoMinuti * 60L);
                    sulMain(() -> p.kick(Testi.c(Testi.kickTroppiTentativi(manca))));
                    return;
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixAuth: tentativo non registrato (" + e.getMessage() + ").");
            }
            riproponi(p, Testi.CREDENZIALI_NO);
            return;
        }

        try {
            dao.azzeraTentativi(stato.ip);
            if (!stato.nome.equals(account.nome)) {
                dao.allineaNome(account.idSito, stato.nome);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixAuth: pulizia tentativi fallita (" + e.getMessage() + ").");
        }

        boolean serveCodice = politica.serve(account, stato.uuid) && account.haOtp();
        if (serveCodice) {
            stato.fase = Fase.OTP;
            sulMain(() -> {
                p.sendMessage(Testi.c(config.prefisso, Testi.OTP_SERVE));
                p.sendMessage(Testi.c("&7Scrivi &f/otp <codice a sei cifre>"));
            });
            return;
        }

        try {
            ricorda(p, stato, false);
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixAuth: sessione non salvata (" + e.getMessage() + ").");
        }
        sulMain(() -> libera(p, true));
    }

    /** Il codice digitato sul tastierino. */
    public void provaCodice(Player p, String cifre) {
        StatoIngresso stato = stato(p);
        if (stato == null || stato.account == null || stato.occupato) {
            return;
        }
        stato.occupato = true;

        plugin.async(() -> {
            try {
                Account account = stato.account;
                if (account.otpBloccato()) {
                    String manca = TestiDurate.finoA(account.totpBloccatoFino);
                    sulMain(() -> p.sendMessage(Testi.c(config.prefisso, Testi.otpBloccato(manca))));
                    return;
                }
                String segreto = OtpCodici.decifraSegreto(account.totpSecretCifrato, config.chiaveOtpBase64);
                if (segreto == null) {
                    // La chiave in configurazione non apre la busta del sito: e' un errore
                    // di installazione, non del giocatore, e va detto a chi gestisce.
                    plugin.getLogger().severe("MagixAuth: impossibile leggere il segreto OTP di "
                            + stato.nome + ". Controlla database.chiave_otp_base64 (OTP_CHIAVE del sito).");
                    sulMain(() -> p.kick(Testi.c(Testi.KICK_DATABASE)));
                    return;
                }

                long passo = OtpCodici.verifica(OtpCodici.base32Decode(segreto), cifre, account.totpUltimoPasso);
                if (passo < 0) {
                    try {
                        dao.otpFallito(account.idSito, config.tentativiMassimi, config.bloccoMinuti);
                    } catch (SQLException ignored) {
                        // Il conteggio e' un di piu': il codice resta comunque rifiutato.
                    }
                    sulMain(() -> p.sendMessage(Testi.c(config.prefisso, Testi.OTP_NO)));
                    return;
                }

                dao.otpPassoSpeso(account.idSito, passo);
                ricorda(p, stato, true);
                sulMain(() -> libera(p, true));

            } catch (SQLException e) {
                plugin.getLogger().warning("MagixAuth: verifica codice fallita (" + e.getMessage() + ").");
                sulMain(() -> p.kick(Testi.c(Testi.KICK_DATABASE)));
            } finally {
                stato.occupato = false;
            }
        });
    }

    private void riproponi(Player p, String messaggio) {
        sulMain(() -> p.sendMessage(Testi.c(config.prefisso, messaggio)));
    }

    // =================================================================================
    // 5. Uscita dal cancello
    // =================================================================================

    /** Il giocatore ha finito: si riprende il suo posto nel mondo. */
    public void libera(Player p, boolean annuncia) {
        StatoIngresso stato = fermi.remove(p.getUniqueId());
        if (stato == null) {
            return;
        }
        if (stato.taskScadenza != -1) {
            Bukkit.getScheduler().cancelTask(stato.taskScadenza);
        }
        if (stato.taskCartello != -1) {
            Bukkit.getScheduler().cancelTask(stato.taskCartello);
        }
        stato.fase = Fase.LIBERO;

        visibilita.mostra(p);

        riportaAlPosto(p, stato);

        if (annuncia) {
            p.sendMessage(Testi.c(config.prefisso, Testi.DENTRO));
        }
        // Solo adesso il server dice che e' arrivato: prima sarebbe stato l'annuncio di un
        // tentativo, non di un ingresso.
        if (config.ritardaMessaggioIngresso && stato.messaggioIngresso != null) {
            Bukkit.getServer().sendMessage(stato.messaggioIngresso);
        }
    }

    private void riportaAlPosto(Player p, StatoIngresso stato) {
        if (stato.posizioneVera != null) {
            Location dove = stato.posizioneVera;
            plugin.getLogger().info("MagixAuth: riporto " + stato.nome + " a "
                    + dove.getWorld().getName() + " "
                    + Math.round(dove.getX()) + "/" + Math.round(dove.getY())
                    + "/" + Math.round(dove.getZ()) + ".");
            // Con qualche tick di ritardo, e non subito: al momento del login altri plugin
            // stanno ancora sistemando il giocatore (CMI e le fazioni lo fanno al join), e
            // un teletrasporto mandato nello stesso istante viene sovrascritto dal loro.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) {
                    p.teleportAsync(dove);
                }
            }, 5L);
            pulisciPosizione(stato.uuid);
            return;
        }
        plugin.getLogger().info("MagixAuth: nessuna posizione in memoria per " + stato.nome
                + ", la cerco nel database.");
        // In memoria non c'e': puo' essere rientrato dopo essersi disconnesso al cancello,
        // e allora la posizione buona e' quella che avevamo messo nel database.
        plugin.async(() -> {
            try {
                Object[] riga = dao.leggiPosizione(stato.uuid);
                if (riga == null) {
                    return;
                }
                World mondo = Bukkit.getWorld((String) riga[0]);
                if (mondo == null) {
                    return;
                }
                Location dove = new Location(mondo, (Double) riga[1], (Double) riga[2],
                        (Double) riga[3], (Float) riga[4], (Float) riga[5]);
                plugin.getLogger().info("MagixAuth: riporto " + stato.nome
                        + " alla posizione salvata nel database.");
                sulMain(() -> Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline()) {
                        p.teleportAsync(dove);
                    }
                }, 5L));
                dao.cancellaPosizione(stato.uuid);
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixAuth: posizione di " + stato.nome
                        + " non ripristinata (" + e.getMessage() + ").");
            }
        });
    }

    private void pulisciPosizione(UUID uuid) {
        plugin.async(() -> {
            try {
                dao.cancellaPosizione(uuid);
            } catch (SQLException ignored) {
                // Resta una riga vecchia: la prossima entrata la sovrascrive.
            }
        });
    }

    /**
     * Rimette al cancello qualcuno che era gia' entrato.
     *
     * Serve per il "chiudi la sessione di gioco" premuto sul sito: e' il pulsante che si va a
     * cercare quando si sospetta che qualcun altro sia dentro col proprio account, e deve
     * avere effetto entro pochi secondi, non al prossimo ingresso. Il giocatore resta dov'e'
     * — non ha senso spedirlo allo spawn, la sua posizione l'ha gia' vista — ma torna muto,
     * fermo e invisibile finche' non ridigita il codice.
     */
    public void ricongela(Player p, String motivo) {
        if (fermo(p)) {
            return;
        }
        plugin.async(() -> {
            try {
                Account account = dao.perUuid(p.getUniqueId());
                if (account == null || !account.haOtp()) {
                    // Senza un secondo fattore non avrebbe modo di ripassare: rimandarlo al
                    // cancello vorrebbe dire chiuderlo fuori dal gioco finche' non esce.
                    return;
                }
                dao.revocaSessioni(p.getUniqueId());

                String ip = p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
                StatoIngresso stato = new StatoIngresso(p.getUniqueId(), p.getName(), ip, account,
                        ip, null, Fase.OTP);

                sulMain(() -> {
                    if (!p.isOnline()) {
                        return;
                    }
                    fermi.put(p.getUniqueId(), stato);
                    visibilita.nascondi(p);
                    avviaCronometro(p, stato);
                    p.sendMessage(Testi.c(config.prefisso, "&e" + motivo));
                    p.sendMessage(Testi.c("&7Scrivi &f/otp <codice a sei cifre>"));
                });
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixAuth: revoca per " + p.getName()
                        + " non applicata (" + e.getMessage() + ").");
            }
        });
    }

    /** Se ne e' andato prima di finire: si smonta tutto, la posizione resta nel database. */
    public void abbandona(Player p) {
        StatoIngresso stato = fermi.remove(p.getUniqueId());
        decisioni.remove(p.getUniqueId());
        if (stato == null) {
            return;
        }
        if (stato.taskScadenza != -1) {
            Bukkit.getScheduler().cancelTask(stato.taskScadenza);
        }
        if (stato.taskCartello != -1) {
            Bukkit.getScheduler().cancelTask(stato.taskCartello);
        }
    }

    /** Chi e' ancora al cancello quando il server si ferma. */
    public void chiudiTutto() {
        for (UUID uuid : fermi.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            // Niente da chiudere: si entra scrivendo un comando, non aprendo finestre.
        }
        fermi.clear();
        decisioni.clear();
    }

    // -----------------------------------------------------------------------------

    /** L'indirizzo da cui sta arrivando, o vuoto se non si riesce a saperlo. */
    private static String ipDi(Player p) {
        return p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
    }

    private void sulMain(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

}
