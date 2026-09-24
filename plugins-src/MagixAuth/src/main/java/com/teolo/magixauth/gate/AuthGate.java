package com.teolo.magixauth.gate;

import com.teolo.magixauth.AuthConfig;
import com.teolo.magixauth.MagixAuth;
import com.teolo.magixauth.crypt.OtpCodes;
import com.teolo.magixauth.crypt.Password;
import com.teolo.magixauth.db.AuthDao;
import com.teolo.magixauth.lang.Messages;
import com.teolo.magixauth.model.Account;
import com.teolo.magixauth.model.Phase;
import com.teolo.magixauth.premium.MojangLookup;
import com.teolo.magixauth.util.Texts;
import com.teolo.magixauth.util.DurationText;
import io.papermc.paper.connection.PlayerLoginConnection;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
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
    private final OtpPolicy policy;
    private final Visibility visibility;
    private final MojangLookup mojang;
    private final Cookie cookie;
    private final Messages messages;

    /**
     * Il punto esatto in cui compare chi deve ancora fare il login, deciso a mano con
     * /magixauth setspawn. Il punto di spawn del mondo (/setworldspawn) e quello di CMI non
     * sono la stessa cosa: chi vuole il cancello sullo spawn di CMI lo mette qui stando li'.
     * Null finche' nessuno l'ha impostato: allora si ripiega sullo spawn del mondo principale.
     */
    private volatile Location loginSpawn;

    /** Chi e' fermo al cancello adesso. */
    private final Map<UUID, EntryState> frozen = new ConcurrentHashMap<>();

    /** Deciso al pre-login, ritirato al momento dell'ingresso. */
    private final Map<UUID, EntryState> decisions = new ConcurrentHashMap<>();

    public AuthGate(MagixAuth plugin, AuthConfig config, AuthDao dao, OtpPolicy policy,
                    Visibility visibility, MojangLookup mojang, Messages messages) {
        this.plugin = plugin;
        this.config = config;
        this.dao = dao;
        this.policy = policy;
        this.visibility = visibility;
        this.mojang = mojang;
        this.messages = messages;
        this.cookie = new Cookie(plugin, config.cookieWaitMillis);
        this.loginSpawn = readLoginSpawn();
    }

    /** Il file che tiene il punto del cancello fra un avvio e l'altro. */
    private File loginSpawnFile() {
        return new File(plugin.getDataFolder(), "cancello.yml");
    }

    /**
     * Rilegge da disco il punto del cancello, o null se non e' mai stato impostato o se il
     * mondo salvato non esiste piu' (su questo server i mondi si rigenerano da zero).
     */
    private Location readLoginSpawn() {
        File f = loginSpawnFile();
        if (!f.exists()) {
            return null;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        String worldName = y.getString("world");
        if (worldName == null) {
            return null;
        }
        World targetWorld = Bukkit.getWorld(worldName);
        if (targetWorld == null) {
            plugin.getLogger().warning("MagixAuth: il cancello era nel mondo \"" + worldName
                    + "\", che non esiste piu'. Reimpostalo con /magixauth setspawn.");
            return null;
        }
        return new Location(targetWorld, y.getDouble("x"), y.getDouble("y"), y.getDouble("z"),
                (float) y.getDouble("yaw"), (float) y.getDouble("pitch"));
    }

    /**
     * Fissa il cancello dove sta chi ha dato /magixauth setspawn, sguardo compreso, e lo
     * scrive su disco: da qui in poi chi deve fare il login compare esattamente li'.
     */
    public void setLoginSpawn(Location where) {
        Location chosen = where.clone();
        this.loginSpawn = chosen;
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", chosen.getWorld().getName());
        y.set("x", chosen.getX());
        y.set("y", chosen.getY());
        y.set("z", chosen.getZ());
        y.set("yaw", (double) chosen.getYaw());
        y.set("pitch", (double) chosen.getPitch());
        try {
            y.save(loginSpawnFile());
        } catch (IOException e) {
            plugin.getLogger().warning("MagixAuth: cancello non salvato su disco ("
                    + e.getMessage() + "). Vale fino al prossimo riavvio.");
        }
    }

    /** Dov'e' il cancello adesso, o null se si usa ancora lo spawn del mondo. */
    public Location loginSpawn() {
        return loginSpawn == null ? null : loginSpawn.clone();
    }

    public EntryState state(Player p) {
        return frozen.get(p.getUniqueId());
    }

    public boolean isFrozen(Player p) {
        return frozen.containsKey(p.getUniqueId());
    }

    // =================================================================================
    // 1. Pre-login: si decide tutto qui, dove si puo' aspettare il database
    // =================================================================================

    /**
     * @return null se il giocatore puo' proseguire, altrimenti il motivo del rifiuto
     */
    public String decide(UUID proposedUuid, String name, String ip,
                         PlayerLoginConnection conn, ProfileSetter prof) {
        try {
            java.time.LocalDateTime blocked = dao.blockedUntil(ip);
            if (blocked != null) {
                return messages.get(proposedUuid, "gate.kick-too-many-attempts",
                        "time", DurationText.until(blocked));
            }

            Account account = dao.byName(name);

            // L'UUID e' un dato dell'account, non una funzione del nome: chi ha gia' una
            // riga si riprende il suo di sempre, compreso quello premium di quando il
            // server era in online mode. E' cio' che evita ogni migrazione di ops.json,
            // LuckPerms, fazioni e permessi.
            UUID uuid = account != null ? account.uuid : AuthDao.uuidOffline(name);

            // La skin va rimessa a mano: in offline mode il gioco non la chiede piu' a
            // nessuno, e senza questo si entra con la faccia di serie — o, da un launcher
            // non ufficiale, con quella di uno sconosciuto che ha registrato questo nickname
            // sul servizio di skin del launcher. Le si passa anche l'UUID dell'account:
            // quando e' un UUID Mojang, la skin si chiede direttamente con quello.
            String[] skin = null;
            if (config.skinFromMojang) {
                MojangLookup.Found found = mojang.skinOf(uuid, name);
                skin = found.skin();
                if (found.failed()) {
                    // Un buco di rete non deve diventare permanente ne' invisibile: non
                    // viene messo in cache, e almeno si sa perche' quel giocatore e' entrato
                    // senza la sua faccia.
                    plugin.getLogger().warning("MagixAuth: skin di " + name
                            + " non recuperata (" + found.reason() + "). Si riprova al prossimo ingresso.");
                }
            }
            prof.apply(uuid.equals(proposedUuid) ? null : uuid, skin);

            if (Bukkit.getPlayer(uuid) != null) {
                return messages.get(uuid, "gate.kick-name-taken");
            }

            // Il dispositivo si riconosce in DUE modi, e basta che ne funzioni uno.
            //
            // L'indirizzo di rete da solo non basta: su una connessione mobile cambia anche
            // piu' volte in un'ora, e chi usciva cinque minuti prima si ritrovava la password
            // da ridigitare come se fosse a un computer mai visto (successo davvero).
            // Il gettone da solo non basta neppure: vive nella memoria del client e sparisce
            // quando il giocatore chiude il gioco, quindi non riconoscerebbe mai chi rientra
            // il giorno dopo. Uno copre il buco dell'altro.
            String deviceKey = ip;
            String deviceCookie = config.deviceCookie
                    ? cookie.deviceOf(conn) : null;

            Phase phase;
            if (account == null || !account.registered()) {
                phase = Phase.REGISTRAZIONE;
            } else {
                // Computer gia' conosciuto e sessione ancora buona: si salta la password.
                Boolean sessionWithOtp = deviceCookie == null
                        ? null : dao.session(uuid, deviceCookie);
                if (sessionWithOtp == null) {
                    sessionWithOtp = dao.session(uuid, deviceKey);
                }
                boolean needsOtp = policy.required(account, uuid) && account.haOtp();

                if (sessionWithOtp == null) {
                    phase = Phase.PASSWORD;
                } else if (needsOtp && config.otpFollowsSession && !sessionWithOtp) {
                    phase = Phase.OTP;
                } else {
                    phase = Phase.LIBERO;
                }
            }

            EntryState state = new EntryState(uuid, name, ip, account, deviceKey,
                    deviceCookie, phase);
            decisions.put(uuid, state);
            return null;

        } catch (SQLException e) {
            plugin.getLogger().warning("MagixAuth: database non raggiungibile al pre-login di "
                    + name + " (" + e.getMessage() + ").");
            // Non si puo' sapere chi sia: si tiene fuori. Preferibile chiudere fuori il
            // proprietario per qualche minuto che far entrare chiunque col suo nome.
            return messages.get(proposedUuid, "gate.kick-database");
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
    private void remember(Player p, EntryState state, boolean otpOk) throws SQLException {
        dao.saveSession(state.uuid, state.deviceKey, state.ip, config.sessionHours, otpOk);

        if (!config.deviceCookie) {
            return;
        }
        byte[] cookieToken = Cookie.newCookieToken();
        dao.saveSession(state.uuid, Cookie.deviceKey(cookieToken), state.ip, config.sessionHours, otpOk);
        if (state.deviceCookie != null) {
            dao.forgetDevice(state.uuid, state.deviceCookie);
        }
        onMain(() -> cookie.deliver(p, cookieToken));
    }

    /**
     * Il pre-login non puo' toccare il profilo da solo: glielo passa il listener.
     *
     * @param uuid l'UUID da imporre, oppure null se quello proposto va gia' bene
     * @param skin {valore, firma} delle texture, oppure null per lasciare quella di serie
     */
    public interface ProfileSetter {
        void apply(UUID uuid, String[] skin);
    }

    // =================================================================================
    // 2. Spawn: dov'era davvero, e dove lo facciamo comparire
    // =================================================================================

    /**
     * @return la posizione a cui farlo comparire, o null per lasciarlo dov'era
     */
    public Location hijackSpawn(UUID uuid, Location real) {
        EntryState state = decisions.get(uuid);
        if (state == null || state.phase == Phase.LIBERO || !config.spawnInsteadOfPosition) {
            return null;
        }
        state.realPosition = real == null ? null : real.clone();

        // La posizione va anche nel database, e non solo qui in memoria: se si disconnette
        // mentre e' fermo allo spawn, il server salverebbe lo spawn come sua ultima
        // posizione e quella vera sarebbe persa per sempre.
        if (state.realPosition != null && state.realPosition.getWorld() != null) {
            Location l = state.realPosition;
            plugin.async(() -> {
                try {
                    dao.savePosition(uuid, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(),
                            l.getYaw(), l.getPitch());
                } catch (SQLException e) {
                    plugin.getLogger().warning("MagixAuth: posizione di " + state.name
                            + " non salvata (" + e.getMessage() + ").");
                }
            });
        }

        // Il cancello e' UNO solo, per tutti, in qualunque mondo si fossero disconnessi:
        // chi era nell'End o nel Nether NON va portato allo spawn di quel mondo — la
        // piattaforma di ossidiana dell'End non e' un posto dove chiedere una password.
        // La posizione vera e' gia' stata salvata qui sopra, quindi dopo il login torna
        // esattamente dov'era, End compreso.
        //
        // Se qualcuno ha fissato il cancello a mano con /magixauth setspawn — perche' lo
        // spawn di CMI non e' quello di /setworldspawn — si usa quel punto esatto, sguardo
        // compreso. Altrimenti si ripiega sullo spawn del mondo principale.
        Location pinned = this.loginSpawn;
        if (pinned != null && pinned.getWorld() != null) {
            return pinned.clone();
        }

        World targetWorld = Bukkit.getWorlds().get(0);

        // Al CENTRO del blocco, non sul suo spigolo: `getSpawnLocation` restituisce le
        // coordinate intere del blocco, e chi ci viene messo sopra si ritrova incastrato
        // fra quattro blocchi invece che in piedi su uno. Mezzo blocco su ciascun asse
        // rimette il giocatore dove starebbe naturalmente.
        Location where = targetWorld.getSpawnLocation().clone();
        where.setX(where.getBlockX() + 0.5);
        where.setZ(where.getBlockZ() + 0.5);
        // Lo sguardo e' quello dello spawn, non quello che aveva prima di uscire: il
        // cancello e' un posto costruito apposta (cartelli, tastierino) e chi arriva deve
        // trovarselo davanti. `getSpawnLocation` porta con se' l'angolo del punto di spawn
        // (yaw e pitch, quelli che /setworldspawn ha memorizzato), quindi la direzione
        // giusta e' gia' nel clone: basta non sovrascriverla con quella del giocatore.
        return where;
    }

    // =================================================================================
    // 3. Ingresso
    // =================================================================================

    public void welcome(Player p) {
        EntryState state = decisions.remove(p.getUniqueId());
        if (state == null) {
            // Non dovrebbe succedere: vuol dire che il pre-login non e' passato di qui.
            // Nel dubbio si tratta come non autenticato, mai come autenticato.
            state = new EntryState(p.getUniqueId(), p.getName(),
                    p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress(),
                    null, ipOf(p), null, Phase.REGISTRAZIONE);
        }

        if (state.phase == Phase.LIBERO) {
            // Computer conosciuto, sessione valida: entra senza accorgersi di nulla.
            frozen.put(p.getUniqueId(), state);
            release(p, false);
            return;
        }

        frozen.put(p.getUniqueId(), state);
        visibility.hide(p);
        startTimer(p, state);

        // Il pacchetto parte subito, ma non lo si aspetta: al primo ingresso di un giocatore
        // nuovo non fara' in tempo, e il tastierino resta leggibile lo stesso.

        EntryState finalState = state;
        // Un attimo di respiro: al join il client sta ancora ricevendo il mondo, e un
        // messaggio mandato troppo presto scorre via prima che si veda qualcosa.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && isFrozen(p)) {
                instructions(p, finalState);
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
    private void instructions(Player p, EntryState state) {
        if (state.awaitsCode()) {
            p.sendMessage(Texts.c(messages.get(p, "gate.otp-required")));
            p.sendMessage(Texts.c(messages.get(p, "gate.otp-hint")));
        } else if (state.inRegistration()) {
            p.sendMessage(Texts.c(messages.get(p, "gate.welcome-new")));
            p.sendMessage(Texts.c(messages.get(p, "gate.register-hint")));
        } else {
            p.sendMessage(Texts.c(messages.get(p, "gate.welcome-back")));
            p.sendMessage(Texts.c(messages.get(p, "gate.login-hint")));
        }
    }

    private void startTimer(Player p, EntryState state) {
        state.expiryTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && isFrozen(p)) {
                p.kick(Texts.c(messages.get(p, "gate.kick-timeout")));
            }
        }, config.maxSeconds * 20L).getTaskId();

        state.signTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (p.isOnline() && isFrozen(p)) {
                instructions(p, state);
            } else if (state.signTask != -1) {
                Bukkit.getScheduler().cancelTask(state.signTask);
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
    public void tryPassword(Player p, String typed) {
        EntryState state = state(p);
        if (state == null || state.busy) {
            return;
        }
        state.busy = true;
        plugin.async(() -> {
            try {
                checkPassword(p, state, typed);
            } finally {
                state.busy = false;
            }
        });
    }

    /** La registrazione, chiamata da /register quando le due password coincidono. */
    public void register(Player p, String chosen) {
        EntryState state = state(p);
        if (state == null || state.busy) {
            return;
        }
        state.busy = true;
        plugin.async(() -> {
            try {
                register(p, state, chosen);
            } finally {
                state.busy = false;
            }
        });
    }

    private void register(Player p, EntryState state, String typed) {
        Password.Rejection no = Password.whyNot(typed, state.name, config.minPasswordLength);
        if (no != null) {
            reprompt(p, no.key(), no.kv());
            return;
        }

        // Annotazione premium: interessa sapere se il nome appartiene a un account vero,
        // ma non deve impedire la registrazione se Mojang non risponde.
        UUID premium = config.noteUuidPremium ? mojang.lookup(state.name) : null;

        try {
            int id = dao.register(state.uuid, state.name, Password.fingerprint(typed), premium);
            if (id < 0) {
                onMain(() -> p.kick(Texts.c(messages.get(p, "gate.kick-name-taken"))));
                return;
            }
            remember(p, state, false);
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixAuth: registrazione di " + state.name
                    + " fallita (" + e.getMessage() + ").");
            onMain(() -> p.kick(Texts.c(messages.get(p, "gate.kick-database"))));
            return;
        }

        onMain(() -> {
            p.sendMessage(Texts.c(messages.get(p, "gate.register-success")));
            release(p, true);
        });
    }

    private void checkPassword(Player p, EntryState state, String typed) {
        Account account = state.account;
        boolean correct = account != null && Password.matchesHash(typed, account.passwordHash);

        if (!correct) {
            try {
                int falliti = dao.recordFailure(state.ip, state.name,
                        config.maxAttempts, config.lockoutMinutes);
                if (falliti >= config.maxAttempts) {
                    // Il blocco parte adesso, quindi manca esattamente quanto dura.
                    String remaining = DurationText.fromSeconds(config.lockoutMinutes * 60L);
                    onMain(() -> p.kick(Texts.c(messages.get(p, "gate.kick-too-many-attempts", "time", remaining))));
                    return;
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixAuth: tentativo non registrato (" + e.getMessage() + ").");
            }
            reprompt(p, "gate.wrong-password");
            return;
        }

        try {
            dao.resetAttempts(state.ip);
            if (!state.name.equals(account.name)) {
                dao.alignName(account.siteId, state.name);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixAuth: pulizia tentativi fallita (" + e.getMessage() + ").");
        }

        boolean needsCode = policy.required(account, state.uuid) && account.haOtp();
        if (needsCode) {
            state.phase = Phase.OTP;
            onMain(() -> {
                p.sendMessage(Texts.c(messages.get(p, "gate.otp-required")));
                p.sendMessage(Texts.c(messages.get(p, "gate.otp-hint")));
            });
            return;
        }

        try {
            remember(p, state, false);
        } catch (SQLException e) {
            plugin.getLogger().warning("MagixAuth: sessione non salvata (" + e.getMessage() + ").");
        }
        onMain(() -> release(p, true));
    }

    /** Il codice digitato sul tastierino. */
    public void tryCode(Player p, String digits) {
        EntryState state = state(p);
        if (state == null || state.account == null || state.busy) {
            return;
        }
        state.busy = true;

        plugin.async(() -> {
            try {
                Account account = state.account;
                if (account.otpLocked()) {
                    String remaining = DurationText.until(account.totpLockedUntil);
                    onMain(() -> p.sendMessage(Texts.c(messages.get(p, "gate.otp-locked", "time", remaining))));
                    return;
                }
                String secret = OtpCodes.decryptSecret(account.totpSecretCifrato, config.otpKeyBase64);
                if (secret == null) {
                    // La chiave in configurazione non apre la busta del sito: e' un errore
                    // di installazione, non del giocatore, e va detto a chi gestisce.
                    plugin.getLogger().severe("MagixAuth: impossibile leggere il segreto OTP di "
                            + state.name + ". Controlla database.chiave_otp_base64 (OTP_CHIAVE del sito).");
                    onMain(() -> p.kick(Texts.c(messages.get(p, "gate.kick-database"))));
                    return;
                }

                long step = OtpCodes.checkPassword(OtpCodes.base32Decode(secret), digits, account.totpLastStep);
                if (step < 0) {
                    try {
                        dao.otpFailed(account.siteId, config.maxAttempts, config.lockoutMinutes);
                    } catch (SQLException ignored) {
                        // Il conteggio e' un di piu': il codice resta comunque rifiutato.
                    }
                    onMain(() -> p.sendMessage(Texts.c(messages.get(p, "gate.otp-invalid"))));
                    return;
                }

                dao.otpStepSpent(account.siteId, step);
                remember(p, state, true);
                onMain(() -> release(p, true));

            } catch (SQLException e) {
                plugin.getLogger().warning("MagixAuth: verifica codice fallita (" + e.getMessage() + ").");
                onMain(() -> p.kick(Texts.c(messages.get(p, "gate.kick-database"))));
            } finally {
                state.busy = false;
            }
        });
    }

    private void reprompt(Player p, String key, String... kv) {
        onMain(() -> p.sendMessage(Texts.c(messages.get(p, key, kv))));
    }

    // =================================================================================
    // 5. Uscita dal cancello
    // =================================================================================

    /** Il giocatore ha finito: si riprende il suo posto nel mondo. */
    public void release(Player p, boolean announce) {
        EntryState state = frozen.remove(p.getUniqueId());
        if (state == null) {
            return;
        }
        if (state.expiryTask != -1) {
            Bukkit.getScheduler().cancelTask(state.expiryTask);
        }
        if (state.signTask != -1) {
            Bukkit.getScheduler().cancelTask(state.signTask);
        }
        state.phase = Phase.LIBERO;

        visibility.show(p);

        sendBackToPlace(p, state);

        if (announce) {
            p.sendMessage(Texts.c(messages.get(p, "gate.login-success")));
        }
        // Solo adesso il server dice che e' arrivato: prima sarebbe stato l'annuncio di un
        // tentativo, non di un ingresso.
        if (config.delayJoinMessage && state.savedJoinMessage != null) {
            Bukkit.getServer().sendMessage(state.savedJoinMessage);
        }
    }

    private void sendBackToPlace(Player p, EntryState state) {
        if (state.realPosition != null) {
            Location where = state.realPosition;
            plugin.getLogger().info("MagixAuth: riporto " + state.name + " a "
                    + where.getWorld().getName() + " "
                    + Math.round(where.getX()) + "/" + Math.round(where.getY())
                    + "/" + Math.round(where.getZ()) + ".");
            // Con qualche tick di ritardo, e non subito: al momento del login altri plugin
            // stanno ancora sistemando il giocatore (CMI e le fazioni lo fanno al join), e
            // un teletrasporto mandato nello stesso istante viene sovrascritto dal loro.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) {
                    p.teleportAsync(where);
                }
            }, 5L);
            clearPosition(state.uuid);
            return;
        }
        plugin.getLogger().info("MagixAuth: nessuna posizione in memoria per " + state.name
                + ", la cerco nel database.");
        // In memoria non c'e': puo' essere rientrato dopo essersi disconnesso al cancello,
        // e allora la posizione buona e' quella che avevamo messo nel database.
        plugin.async(() -> {
            try {
                Object[] row = dao.readPosition(state.uuid);
                if (row == null) {
                    return;
                }
                World targetWorld = Bukkit.getWorld((String) row[0]);
                if (targetWorld == null) {
                    return;
                }
                Location where = new Location(targetWorld, (Double) row[1], (Double) row[2],
                        (Double) row[3], (Float) row[4], (Float) row[5]);
                plugin.getLogger().info("MagixAuth: riporto " + state.name
                        + " alla posizione salvata nel database.");
                onMain(() -> Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline()) {
                        p.teleportAsync(where);
                    }
                }, 5L));
                dao.deletePosition(state.uuid);
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixAuth: posizione di " + state.name
                        + " non ripristinata (" + e.getMessage() + ").");
            }
        });
    }

    private void clearPosition(UUID uuid) {
        plugin.async(() -> {
            try {
                dao.deletePosition(uuid);
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
    public void refreeze(Player p, String reasonKey) {
        if (isFrozen(p)) {
            return;
        }
        plugin.async(() -> {
            try {
                Account account = dao.byUuid(p.getUniqueId());
                if (account == null || !account.haOtp()) {
                    // Senza un secondo fattore non avrebbe modo di ripassare: rimandarlo al
                    // cancello vorrebbe dire chiuderlo fuori dal gioco finche' non esce.
                    return;
                }
                dao.revokeSessions(p.getUniqueId());

                String ip = p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
                EntryState state = new EntryState(p.getUniqueId(), p.getName(), ip, account,
                        ip, null, Phase.OTP);

                onMain(() -> {
                    if (!p.isOnline()) {
                        return;
                    }
                    frozen.put(p.getUniqueId(), state);
                    visibility.hide(p);
                    startTimer(p, state);
                    p.sendMessage(Texts.c("&e" + messages.get(p, reasonKey)));
                    p.sendMessage(Texts.c(messages.get(p, "gate.otp-hint")));
                });
            } catch (SQLException e) {
                plugin.getLogger().warning("MagixAuth: revoca per " + p.getName()
                        + " non applicata (" + e.getMessage() + ").");
            }
        });
    }

    /** Se ne e' andato prima di finire: si smonta tutto, la posizione resta nel database. */
    public void abandon(Player p) {
        EntryState state = frozen.remove(p.getUniqueId());
        decisions.remove(p.getUniqueId());
        if (state == null) {
            return;
        }
        if (state.expiryTask != -1) {
            Bukkit.getScheduler().cancelTask(state.expiryTask);
        }
        if (state.signTask != -1) {
            Bukkit.getScheduler().cancelTask(state.signTask);
        }
    }

    /** Chi e' ancora al cancello quando il server si ferma. */
    public void closeAll() {
        for (UUID uuid : frozen.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            // Niente da chiudere: si entra scrivendo un comando, non aprendo finestre.
        }
        frozen.clear();
        decisions.clear();
    }

    // -----------------------------------------------------------------------------

    /** L'indirizzo da cui sta arrivando, o vuoto se non si riesce a saperlo. */
    private static String ipOf(Player p) {
        return p.getAddress() == null ? "" : p.getAddress().getAddress().getHostAddress();
    }

    private void onMain(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

}
