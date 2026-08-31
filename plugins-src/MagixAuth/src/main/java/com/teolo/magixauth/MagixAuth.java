package com.teolo.magixauth;

import com.teolo.magixauth.command.CambiaPasswordCommand;
import com.teolo.magixauth.command.LoginCommand;
import com.teolo.magixauth.command.LogoutCommand;
import com.teolo.magixauth.command.OtpCommand;
import com.teolo.magixauth.command.RegisterCommand;
import com.teolo.magixauth.command.MauthCommand;
import com.teolo.magixauth.db.AuthDao;
import com.teolo.magixauth.db.Database;
import com.teolo.magixauth.gate.AuthGate;
import com.teolo.magixauth.gate.ConnessioneListener;
import com.teolo.magixauth.gate.FreezeListener;
import com.teolo.magixauth.gate.OtpPolicy;
import com.teolo.magixauth.gate.Visibilita;
import com.teolo.magixauth.premium.MojangLookup;
import com.teolo.magixauth.util.StaffGuide;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

/**
 * Registrazione e login per il server non premium.
 *
 * L'account e' lo stesso del sito: la password sta in `users.password_hash` di
 * magicadventure.it, e chi si registra qui puo' entrare li' con lo stesso nome. Non esiste
 * un'anagrafica separata dei giocatori, di proposito — una sola password, un solo posto in
 * cui cambiarla, nessuna sincronizzazione da tenere in piedi.
 *
 * Il pezzo delicato e' AuthGate: leggere quello per capire come funziona l'ingresso.
 */
public final class MagixAuth extends JavaPlugin {

    private AuthConfig config;
    private Database database;
    private AuthDao dao;
    private OtpPolicy politica;
    private AuthGate gate;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = new AuthConfig(getConfig());

        database = new Database(config);
        if (!database.raggiungibile()) {
            // Senza database non si puo' sapere chi sia nessuno. Meglio non partire affatto
            // che partire con un cancello che lascia passare tutti.
            getLogger().severe("MagixAuth: database non raggiungibile. Controlla la sezione "
                    + "database nella configurazione. Il plugin resta spento.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        dao = new AuthDao(database);

        if (!config.chiaveOtpPronta()) {
            // Non e' fatale: chi non usa la verifica in due passaggi entra lo stesso. Ma per
            // gli amministratori lo e', ed e' meglio scoprirlo adesso che al primo ingresso.
            getLogger().warning("MagixAuth: database.otp_key_base64 mancante o non valida. "
                    + "Nessun codice di verifica potra' essere controllato: copiala da "
                    + "OTP_CHIAVE in website/config.php.");
        }

        politica = new OtpPolicy(this, config);
        politica.aggiornaGruppi();
        // La composizione della track cambia una volta ogni mai: si rilegge ogni cinque
        // minuti, e non a ogni ingresso.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                () -> politica.aggiornaGruppi(), 6000L, 6000L);

        Visibilita visibilita = new Visibilita(this, config);
        MojangLookup mojang = new MojangLookup(config.premiumTimeoutMillis, config.skinMinutiCache, getLogger());
        // Il primo collegamento HTTPS della JVM e' il piu' lento: lo si fa adesso, a vuoto,
        // cosi' non lo paga il primo giocatore che entra (e non resta senza skin).
        if (config.skinDaMojang) {
            getServer().getScheduler().runTaskAsynchronously(this, mojang::scalda);
        }
        gate = new AuthGate(this, config, dao, politica, visibilita, mojang);

        // "Chiudi la sessione di gioco" premuto sul sito: si guarda spesso, perche' chi
        // preme quel pulsante ha fretta — sospetta che qualcun altro sia dentro col suo
        // account. La query costa poco quando non c'e' niente da raccogliere.
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::raccogliRevoche,
                200L, config.controlloRevocheSecondi * 20L);

        getServer().getPluginManager().registerEvents(
                new ConnessioneListener(config, gate, visibilita), this);
        getServer().getPluginManager().registerEvents(new FreezeListener(config, gate), this);

        MauthCommand mauth = new MauthCommand(this, config, dao, politica, gate);
        getCommand("magixauth").setExecutor(mauth);
        getCommand("magixauth").setTabCompleter(mauth);
        getCommand("login").setExecutor(new LoginCommand(config, gate));
        getCommand("register").setExecutor(new RegisterCommand(config, gate));
        getCommand("otp").setExecutor(new OtpCommand(config, gate));
        getCommand("logout").setExecutor(new LogoutCommand(this, config, dao, gate));
        getCommand("changepassword").setExecutor(
                new CambiaPasswordCommand(this, config, dao, gate));

        async(() -> {
            try {
                int potate = dao.potaSessioniScadute();
                if (potate > 0) {
                    getLogger().info("MagixAuth: " + potate + " sessioni scadute rimosse.");
                }
            } catch (SQLException e) {
                getLogger().warning("MagixAuth: pulizia sessioni non riuscita (" + e.getMessage() + ").");
            }
        });

        // Il README nella cartella del plugin non si copia piu' dal jar: lo genera
        // StaffGuide insieme al capitolo per il sito, cosi' i due non possono divergere.
        // Capitolo della guida per amministratori sul sito (vedi plugins-src/GUIDA-STAFF.md).
        async(this::scriviGuidaStaff);

        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            // Il plugin e' stato caricato a caldo con gente gia' collegata: quella gente non
            // e' passata dal cancello e non lo passera'. Va detto chiaro, perche' e' una
            // finestra in cui il server e' senza autenticazione.
            getLogger().warning("MagixAuth: caricato con " + Bukkit.getOnlinePlayers().size()
                    + " giocatori gia' collegati, che NON hanno fatto il login. "
                    + "Su un server in offline mode il plugin va caricato all'avvio, mai a caldo.");
        }

        getLogger().info("MagixAuth pronto (account condiviso con magicadventure.it).");
    }


    /**
     * Capitolo di MagixAuth nella guida del gestionale: si riscrive a ogni avvio, quindi
     * comandi, permessi e valori non possono divergere dal plugin in funzione.
     */
    private void scriviGuidaStaff() {
        StaffGuide.crea(this, "MagixAuth — accesso e password", 20)
                // Numeri presi dal config vero: cambiando una chiave, questo capitolo
                // sulla guida del gestionale cambia da solo (vedi util/ConfigValues).
                .valori(new com.teolo.magixauth.util.ConfigValues(this))
                .intro("Il cancello del server. Su un server non premium il nome non prova niente: finché uno "
                        + "non ha fatto il login è congelato e non può fare nulla.")

                .sezione("Un solo account, due porte",
                        "L'account è lo **STESSO** del sito: la password vale su magicadventure.it e in partita, e "
                                + "cambiarla in un posto la cambia nell'altro. Non esistono due registrazioni da "
                                + "tenere allineate.",
                        "Chi entra per la prima volta si registra con /register scegliendo la password; da lì in "
                                + "poi entra con /login. Il primo ingresso è anche il momento in cui nasce il suo "
                                + "account sul sito.")

                .sezione("Il congelamento",
                        "Prima del login il giocatore non si muove, non parla, non lo si vede e non vede gli "
                                + "altri: è il motivo per cui un server offline può stare in piedi. Ha un tempo "
                                + "limite per farcela, poi viene espulso.",
                        "Se il plugin viene caricato a caldo con gente già collegata, quella gente NON è passata "
                                + "dal cancello e non ci passerà: è una finestra senza autenticazione. Il plugin lo "
                                + "scrive nel log a caratteri chiari.")

                .sezione("La verifica in due passaggi",
                        "Chi ha un ruolo delicato passa anche dal codice. La chiave dei codici è la stessa del "
                                + "sito, quindi un codice speso di là risulta speso anche di qua: è voluto, ed è "
                                + "quello che impedisce di riusare due volte lo stesso codice su due porte diverse.",
                        "Se la chiave manca o è sbagliata, nessun codice può essere verificato e il plugin lo "
                                + "avvisa all'avvio. Si copia dal config del sito, non si inventa.")

                .sezione("Account premium",
                        "Chi ha un account Minecraft vero viene riconosciuto interrogando Mojang: serve a evitare "
                                + "che qualcuno si prenda il nome di un giocatore premium che potrebbe arrivare "
                                + "dopo.")

                .sottocomandi("I comandi di amministrazione (/mauth)",
                        "/mauth info <nome>", "Stato di quell'account: se è registrato, quando è entrato l'ultima volta, se ha la verifica attiva.",
                        "/mauth register <nome> <password>", "Registra un account a mano, per i casi che non si risolvono da soli.",
                        "/mauth reset <nome>", "Azzera la password: il giocatore ne sceglie una nuova al prossimo ingresso. È la risposta a «ho dimenticato la password».",
                        "/mauth sessions <nome>", "Le sessioni aperte di quell'account.",
                        "/mauth reload", "Ricarica la configurazione senza riavviare.")

                .comandi()
                .permessi()
                .impostazioni(
                        "login.max_seconds", "Quanti secondi ha un giocatore per fare il login prima di essere espulso.",
                        "login.max_attempts", "Quante password sbagliate prima del blocco temporaneo.",
                        "login.lockout_minutes", "Per quanti minuti resta bloccato dopo troppi tentativi.",
                        "login.min_password_length", "Lunghezza minima della password.",
                        "login.session_hours", "Per quante ore vale una sessione gia' autenticata.",
                        "login.device_cookie", "Se il computer si riconosce anche da un gettone del client, oltre che dall'indirizzo di rete.",
                        "login.cookie_wait_millis", "Quanto si aspetta il gettone dal client prima di passare all'indirizzo.",
                        "otp.order", "Se il codice si chiede prima o dopo la password: `password-first` (consigliato) oppure `otp-first`.",
                        "otp.optional_for_players", "Se la verifica in due passaggi e' obbligatoria solo per lo staff.",
                        "premium.skin_from_mojang", "Se la skin degli account premium veri viene presa da Mojang.",
                        "premium.skin_cache_minutes", "Ogni quanti minuti la skin gia' presa viene richiesta di nuovo a Mojang.")

                .guasto("«In gioco ho addosso la skin di un'altra persona»",
                        "La skin la mette il server, perché in offline mode il gioco non la chiede più a "
                                + "nessuno. Se la richiesta a Mojang non riesce, chi entra da un launcher non "
                                + "ufficiale si ritrova la skin che quel launcher tiene registrata per quel "
                                + "nickname: quella di uno sconosciuto. Nel log c'è la riga «skin di ... non "
                                + "recuperata»; si riprova da sola al prossimo ingresso.")
                .guasto("«Ho cambiato skin su minecraft.net ma in gioco è ancora la vecchia»",
                        "La skin viene tenuta da parte per premium.skin_cache_minutes minuti. Basta aspettare "
                                + "quel tempo e rientrare: non serve riavviare il server.")
                .guasto("«Mi richiede la password anche se sono uscito pochi minuti fa»",
                        "Il computer si riconosce dall'indirizzo di rete e da un gettone tenuto dal client. "
                                + "Se l'indirizzo è cambiato (succede spesso sulle connessioni mobili) e il "
                                + "giocatore ha CHIUSO il gioco — cosa che cancella il gettone — non resta "
                                + "nessuno dei due segni e la password viene richiesta. È voluto: l'alternativa "
                                + "sarebbe far entrare senza password chiunque conosca il nick.")
                .guasto("«Ho dimenticato la password»",
                        "/mauth reset <nome>: la password viene azzerata e il giocatore ne imposta una nuova al "
                                + "prossimo ingresso. Vale anche per il sito, perché l'account è lo stesso.")
                .guasto("«Qualcuno è entrato col mio account»",
                        "Dal sito, in Sicurezza, c'è il pulsante che chiude la sessione di gioco: entro pochi "
                                + "secondi chi è in partita si ritrova bloccato e senza password non prosegue. "
                                + "Poi si azzera la password.")
                .guasto("I codici della verifica non vengono mai accettati",
                        "Manca o è sbagliata la chiave dei codici nel config: va copiata da quella del sito. Il "
                                + "plugin lo segnala nel log all'avvio.")
                .guasto("Un giocatore resta bloccato senza poter scrivere nulla",
                        "È il congelamento: sta cercando di fare qualcosa prima del login. /mauth info dice a che "
                                + "punto è.")

                .mai("Non caricare MagixAuth a caldo con gente già collegata: chi è dentro non passa dal cancello "
                        + "e resta senza autenticazione fino al riavvio.")
                .mai("Non reintrodurre un comando tipo /link: in offline mode basterebbe entrare col nome di un "
                        + "altro per prendersi il suo account sul sito.")
                .mai("Non dettare mai una password a voce in chat pubblica, nemmeno una provvisoria: usa il reset.")
                .scrivi();
    }

    @Override
    public void onDisable() {
        if (gate != null) {
            gate.chiudiTutto();
        }
        if (database != null) {
            database.close();
        }
    }

    /** Rilegge la configurazione. Non tocca chi e' gia' fermo al cancello. */
    public void ricarica() {
        reloadConfig();
        config = new AuthConfig(getConfig());
        politica.aggiornaGruppi();
        Readme.rigenera(this, config);
    }

    /**
     * Manda un lavoro fuori dal thread principale.
     *
     * Serve piu' spesso di quanto sembri: una verifica bcrypt costa qualche centinaio di
     * millisecondi, e farla nel thread del gioco vorrebbe dire fermare il server intero a
     * ogni password digitata da chiunque.
     */
    public void async(Runnable lavoro) {
        if (isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(this, lavoro);
        }
    }

    /** Applica i "chiudi la sessione di gioco" chiesti dal sito. */
    private void raccogliRevoche() {
        try {
            for (java.util.UUID uuid : dao.raccogliRevoche()) {
                org.bukkit.entity.Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) {
                    // Non e' in partita: la sessione l'abbiamo gia' cancellata, al prossimo
                    // ingresso ripassera' dal codice comunque.
                    continue;
                }
                getLogger().info("MagixAuth: sessione di gioco chiusa dal sito per "
                        + p.getName() + ", verifica richiesta di nuovo.");
                gate.ricongela(p, "La sessione di gioco e' stata chiusa dal sito: verificati di nuovo.");
            }
        } catch (SQLException e) {
            getLogger().warning("MagixAuth: revoche non raccolte (" + e.getMessage() + ").");
        }
    }

    public AuthConfig configurazione() {
        return config;
    }

    public AuthGate gate() {
        return gate;
    }
}
