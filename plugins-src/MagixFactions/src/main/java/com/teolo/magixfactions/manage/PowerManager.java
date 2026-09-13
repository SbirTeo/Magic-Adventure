package com.teolo.magixfactions.manage;

import com.teolo.magixfactions.db.Database;
import com.teolo.magixfactions.model.Faction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.teolo.magixfactions.db.DbExecutor;

/**
 * Modulo 3 - Potenza (Power) dei giocatori.
 * <ul>
 *   <li>Ogni giocatore ha un Power intero, da -maxPower a +maxPower (default {@code power.max}=10,
 *       alzabile col permesso {@code magixfactions.power.powermax.<numero>}; la Potenza attuale si
 *       corregge con {@code /mf admin setpower}).</li>
 *   <li>Sale di {@code power.gain-amount} ogni {@code power.gain-interval-seconds} online. La velocita'
 *       e' personale: chi ha il permesso {@code magixfactions.power.speed.<percentuale>} recupera piu'
 *       in fretta (200 = doppia velocita', cioe' meta' tempo). Vedi {@link #speedPercent(Player)}.</li>
 *   <li>Alla morte perde {@code power.death-loss}.</li>
 *   <li>Offline decade di {@code power.offline-decay.amount} per {@code hour|day|week|month} (di serie 1
 *       al giorno), alla velocita' personale dettata da {@code magixfactions.power.speed.loss.<%>}. Il
 *       calo avviene mentre il giocatore e' VIA (vedi {@link #tickOffline()}), non al suo rientro.</li>
 * </ul>
 * Il Power di fazione e' la somma dei Power dei membri; il maxPower di fazione la somma dei loro maxPower.
 */
public final class PowerManager {

    /** Stato in cache di un giocatore (rispecchia la tabella players). */
    private static final class PP {
        String name;
        int power;
        int maxPower;
        long lastSeen;
        long lastLogin;    // ultimo accesso REALE (join/quit), NON toccato dal decadimento: vedi ScoreManager.isActive
        int mapRows;   // colonna storica 'map_rows' riusata come ZOOM mappa: 0=default config, 1..5=closest..farthest
        int progress;      // avanzamento verso il prossimo punto di Potenza, in "secondi x percentuale"
        boolean minimapHidden; // il giocatore ha SPENTO la minimap HUD con /f minimap off (colonna minimap_hidden)
        PP(String name, int power, int maxPower, long lastSeen, int mapRows, int progress) {
            this.name = name; this.power = power; this.maxPower = maxPower; this.lastSeen = lastSeen;
            this.mapRows = mapRows; this.progress = progress;
        }
    }

    private final JavaPlugin plugin;
    private final Database db;
    private final DbExecutor dbExec;
    private final Map<UUID, PP> cache = new HashMap<>();
    /** Giocatori con un'attivazione minimap gia' in coda (vedi {@link #reattachMinimap(Player)}). */
    private final java.util.Set<UUID> minimapPending = new java.util.HashSet<>();
    private com.teolo.magixfactions.map.MapService mapService; // impostato dopo la costruzione (per aggiornare la mappa in mano al join)
    private com.teolo.magixfactions.minimap.MinimapManager minimapManager; // impostato dopo la costruzione (riattacco minimap al join)
    private com.teolo.magixfactions.resourcepack.ResourcePackService resourcePack; // idem, per rimandare il resource pack al join
    private com.teolo.magixfactions.lang.Messages messages; // impostato dopo la costruzione (avviso Potenza alla morte)
    private com.teolo.magixfactions.hook.LuckPermsHook luckPerms; // permessi dei giocatori OFFLINE (vedi tickOffline)
    private FactionManager factionManager; // per "chiudere" l'integrale della potenza media prima di un cambio Potenza
    private ScoreManager scoreManager;     // (media esatta: stesso concetto della banca)

    // Zoom mappa = BLOCCHI PER PIXEL, valore numerico libero (0.25, 0.5, 1, ...), niente piu' preset
    // (closest/close/...). Salvato sulla colonna storica map_rows come intero = round(bpp*100): 0 = usa
    // il default di config map.default-zoom. Vedi {@link MapService} per come lo zoom (double) e' usato.
    static final double DEFAULT_ZOOM = 0.25;

    public PowerManager(JavaPlugin plugin, Database db, DbExecutor dbExec) {
        this.plugin = plugin; this.db = db; this.dbExec = dbExec;
    }

    public void setMapService(com.teolo.magixfactions.map.MapService mapService) { this.mapService = mapService; }
    public void setMinimapManager(com.teolo.magixfactions.minimap.MinimapManager minimapManager) { this.minimapManager = minimapManager; }
    public void setResourcePack(com.teolo.magixfactions.resourcepack.ResourcePackService resourcePack) { this.resourcePack = resourcePack; }
    public void setMessages(com.teolo.magixfactions.lang.Messages messages) { this.messages = messages; }
    public void setLuckPerms(com.teolo.magixfactions.hook.LuckPermsHook luckPerms) { this.luckPerms = luckPerms; }
    public void setFactionManager(FactionManager factionManager) { this.factionManager = factionManager; }
    public void setScoreManager(ScoreManager scoreManager) { this.scoreManager = scoreManager; }

    /** Chiude l'integrale della media (potenza sempre, banca se online) della fazione di {@code u} FINO A
     *  ORA, PRIMA che la Potenza del giocatore cambi: cosi' il valore vecchio pesa per il tempo esatto in
     *  cui e' stato tenuto (media esatta, stesso concetto della banca in {@link FactionManager#setBank}). */
    private void flushFaction(UUID u) {
        if (factionManager == null || scoreManager == null) return;
        Faction f = factionManager.getFaction(u);
        if (f != null) scoreManager.flush(f);
    }

    /** Ogni quanti minuti gira {@link #tickOffline()}. Minimo 1. */
    public int offlineRefreshMinutes() {
        return Math.max(1, plugin.getConfig().getInt("power.offline-refresh-minutes", 10));
    }

    private int startPower()   { return plugin.getConfig().getInt("power.start", 0); }
    private int defaultMax()   { return plugin.getConfig().getInt("power.max", 10); }
    private int gainAmount()   { return plugin.getConfig().getInt("power.gain-amount", 1); }
    private int deathLoss()    { return plugin.getConfig().getInt("power.death-loss", 4); }
    public  int gainIntervalSeconds() { return Math.max(1, plugin.getConfig().getInt("power.gain-interval-seconds", 600)); }
    /** Ogni quanti secondi gira {@link #tickOnline()}: e' solo la GRANULARITA' del conteggio, non la
     *  velocita' (che resta {@code gain-interval-seconds} per un giocatore normale). */
    public  int tickSeconds() {
        int t = plugin.getConfig().getInt("power.tick-seconds", 20);
        return Math.max(1, Math.min(gainIntervalSeconds(), t));
    }

    // --------------------------- PERMESSI VIP ----------------------------
    // I vantaggi VIP sono PERMESSI, non impostazioni per-giocatore: si danno e si tolgono dal gruppo
    // (LuckPerms), valgono per tutto il gruppo insieme e non lasciano righe da correggere a mano quando
    // un abbonamento scade. Stesso schema numerico gia' usato da magixfactions.members.<numero> per il
    // limite membri; per la regola di conflitto vedi Raccolta qui sotto.

    /** Tetto di Potenza del giocatore: {@code magixfactions.power.powermax.20} = maxpower 20. */
    private static final String PERM_MAXPOWER = "magixfactions.power.powermax.";
    /** Velocita' con cui si PERDE Potenza da offline: {@code ...speed.loss.50} = meta' (ci mette il doppio). */
    private static final String PERM_LOSS = "magixfactions.power.speed.loss.";
    /** Velocita' con cui si RECUPERA Potenza online: {@code magixfactions.power.speed.200} = doppia. */
    private static final String PERM_SPEED = "magixfactions.power.speed.";
    /** Minimap HUD nell'angolo dello schermo: permesso secco, senza numero. */
    public static final String PERM_MINIMAP = "magixfactions.minimap";

    /** I tre valori che i permessi dettano per un giocatore. */
    public static final class Vantaggi {
        public final int maxPower;  // tetto di Potenza
        public final int speed;     // % di velocita' di RECUPERO online (100 = normale)
        public final int loss;      // % di velocita' di PERDITA da offline (100 = normale)
        Vantaggi(int maxPower, int speed, int loss) { this.maxPower = maxPower; this.speed = speed; this.loss = loss; }
    }

    /**
     * Raccoglitore dei permessi numerici mentre li si scorre. Regola unica per tutti e tre:
     * <b>fra i permessi POSSEDUTI vince il piu' favorevole al giocatore</b> (il piu' alto per tetto e
     * recupero, il piu' BASSO per la perdita); se non ne possiede nessuno vale il valore di config.
     * Quindi un valore scritto in un permesso fa sempre qualcosa — anche peggiorativo, se e' l'unico
     * che il giocatore ha (es. il solo {@code speed.loss.200} = perde il doppio).
     */
    private static final class Raccolta {
        Integer maxPower, speed, loss;   // null = nessun permesso di quel tipo

        void offri(String n) {
            // L'ordine conta: "...speed.loss.<n>" comincia per "...speed.", quindi il prefisso piu'
            // lungo va provato per primo, altrimenti la perdita verrebbe letta come recupero.
            if (n.startsWith(PERM_LOSS)) {
                Integer v = number(n, PERM_LOSS);
                if (v != null) loss = (loss == null ? v : Math.min(loss, v));
            } else if (n.startsWith(PERM_SPEED)) {
                Integer v = number(n, PERM_SPEED);
                if (v != null) speed = (speed == null ? v : Math.max(speed, v));
            } else if (n.startsWith(PERM_MAXPOWER)) {
                Integer v = number(n, PERM_MAXPOWER);
                if (v != null) maxPower = (maxPower == null ? v : Math.max(maxPower, v));
            }
        }

        /** La coda numerica del permesso, o null se non e' un numero (es. un nodo scritto male). */
        private static Integer number(String permesso, String prefisso) {
            try {
                int v = Integer.parseInt(permesso.substring(prefisso.length()));
                return v < 0 ? null : Math.min(10000, v);
            } catch (NumberFormatException e) { return null; }
        }
    }

    private Vantaggi risolvi(Raccolta r) {
        return new Vantaggi(r.maxPower == null ? defaultMax() : r.maxPower,
                            r.speed == null ? 100 : r.speed,
                            r.loss == null ? 100 : r.loss);
    }

    /**
     * I vantaggi di un giocatore ONLINE, letti in un solo giro sui suoi permessi effettivi. Un giro e non
     * tre perche' questo e' il percorso caldo ({@link #tickOnline()} lo chiama per ogni giocatore online
     * a ogni tick) e l'elenco dei permessi di un utente LuckPerms non e' cortissimo.
     */
    public Vantaggi vantaggi(Player p) {
        Raccolta r = new Raccolta();
        for (org.bukkit.permissions.PermissionAttachmentInfo pi : p.getEffectivePermissions()) {
            if (pi.getValue()) r.offri(pi.getPermission());
        }
        return risolvi(r);
    }

    /** I vantaggi di un giocatore OFFLINE, dai permessi letti via LuckPerms ({@link LuckPermsHook}). */
    private Vantaggi vantaggi(Map<String, Boolean> permissions) {
        Raccolta r = new Raccolta();
        for (Map.Entry<String, Boolean> e : permissions.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) r.offri(e.getKey());
        }
        return risolvi(r);
    }

    /** Secondi di gioco che servono a QUEL giocatore per guadagnare {@code gain-amount} di Potenza. */
    public int secondsPerGain(Player p) {
        int speed = Math.max(1, vantaggi(p).speed);
        return Math.max(1, (int) ((long) gainIntervalSeconds() * 100 / speed));
    }

    // ------------------------------- LOAD --------------------------------
    public void loadAll() throws SQLException {
        cache.clear();
        try (Connection c = db.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT uuid, name, power, max_power, last_seen, map_rows, power_progress, last_login, minimap_hidden FROM players")) {
            while (rs.next()) {
                UUID u = UUID.fromString(rs.getString("uuid"));
                PP pp = new PP(rs.getString("name"),
                        (int) Math.round(rs.getDouble("power")),
                        (int) Math.round(rs.getDouble("max_power")),
                        rs.getLong("last_seen"),
                        rs.getInt("map_rows"),
                        rs.getInt("power_progress"));
                // Righe pre-feature: last_login a 0 -> ripiega su last_seen (valore ragionevole "di recente"),
                // cosi' non risultano subito tutte inattive; da qui in avanti last_login e' pulito (join/quit).
                long ll = rs.getLong("last_login");
                pp.lastLogin = ll > 0 ? ll : pp.lastSeen;
                pp.minimapHidden = rs.getInt("minimap_hidden") != 0;
                cache.put(u, pp);
            }
        }
    }

    private PP ensure(UUID u) {
        PP pp = cache.get(u);
        if (pp != null) return pp;
        pp = new PP(null, startPower(), defaultMax(), System.currentTimeMillis(), 0, 0);
        pp.lastLogin = pp.lastSeen;
        cache.put(u, pp);
        // Cattura i valori qui (main thread) e scrivi in async, serializzato: l'INSERT resta ordinato
        // prima di qualsiasi UPDATE successivo dello stesso giocatore.
        final String us = u.toString();
        final int power = pp.power, maxPower = pp.maxPower, mapRows = pp.mapRows, progress = pp.progress;
        final long lastSeen = pp.lastSeen, lastLogin = pp.lastLogin;
        dbExec.submit(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO players (uuid, name, power, max_power, last_seen, map_rows, power_progress, last_login) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, us); ps.setString(2, null);
                ps.setDouble(3, power); ps.setDouble(4, maxPower); ps.setLong(5, lastSeen); ps.setInt(6, mapRows);
                ps.setInt(7, progress); ps.setLong(8, lastLogin);
                ps.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().warning("[Power] insert giocatore: " + e.getMessage()); }
        });
        return pp;
    }

    // ------------------------------ QUERY --------------------------------
    public int getPower(UUID u)    { PP pp = cache.get(u); return pp == null ? startPower() : pp.power; }
    public int getMaxPower(UUID u) { PP pp = cache.get(u); return pp == null ? defaultMax() : pp.maxPower; }

    public int factionPower(Faction f) {
        int sum = 0;
        for (UUID u : f.getMembers().keySet()) sum += getPower(u);
        return sum;
    }

    public int factionMaxPower(Faction f) {
        int sum = 0;
        for (UUID u : f.getMembers().keySet()) sum += getMaxPower(u);
        return sum;
    }

    // ---------------------------- ADMIN ----------------------------------
    /** UUID del giocatore con quel nome, se ha una riga (cioe' e' entrato almeno una volta). */
    public UUID findByName(String name) {
        for (Map.Entry<UUID, PP> e : cache.entrySet()) {
            if (name.equalsIgnoreCase(e.getValue().name)) return e.getKey();
        }
        return null;
    }

    /**
     * (solo dati di test) Registra un giocatore FINTO: crea la sua riga {@code players} col nome, la
     * Potenza e il tetto indicati (senza passare per {@code ensure}, che lascerebbe il nome nullo), e la
     * mette in cache come se fosse un giocatore visto di recente. Lo usa {@link FakeDataManager}; per un
     * giocatore vero la riga la crea da sola {@link #onJoin}. Rimosso da {@link #forget}.
     */
    public void registerFake(UUID u, String name, int power, int maxPower) {
        long now = System.currentTimeMillis();
        PP pp = new PP(name, power, maxPower, now, 0, 0);
        pp.lastLogin = now;
        clamp(pp);
        cache.put(u, pp);
        final String us = u.toString(); final String nm = pp.name;
        final int pw = pp.power, mx = pp.maxPower; final long ls = pp.lastSeen, ll = pp.lastLogin;
        dbExec.submit(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO players (uuid, name, power, max_power, last_seen, map_rows, power_progress, last_login) VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, us); ps.setString(2, nm);
                ps.setDouble(3, pw); ps.setDouble(4, mx); ps.setLong(5, ls); ps.setInt(6, 0);
                ps.setInt(7, 0); ps.setLong(8, ll);
                ps.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().warning("[Power] insert giocatore finto: " + e.getMessage()); }
        });
    }

    /**
     * (solo dati di test) Cancella del tutto un giocatore FINTO: lo toglie dalla cache e ne rimuove la riga
     * {@code players}. Da usare SOLO su UUID creati con {@link #registerFake}. Vedi {@link FakeDataManager#clearAll}.
     */
    public void forget(UUID u) {
        cache.remove(u);
        final String us = u.toString();
        dbExec.submit(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("DELETE FROM players WHERE uuid=?")) {
                ps.setString(1, us);
                ps.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().warning("[Power] rimozione giocatore finto: " + e.getMessage()); }
        });
    }

    /** (admin) Imposta la Potenza attuale del giocatore (clampata al suo maxPower). */
    public void setPower(UUID u, int value) {
        flushFaction(u);   // accredita la potenza vecchia fino a ora, poi cambia (media esatta)
        PP pp = ensure(u);
        pp.power = value;
        clamp(pp);
        save(u);
    }

    /**
     * Riallinea il tetto (maxPower) del giocatore a quello che dicono i suoi PERMESSI, e riclampa la
     * Potenza attuale. Il valore resta scritto nella riga {@code players} perche' serve anche da OFFLINE
     * (il maxPower di fazione e' la somma di quello di TUTTI i membri, non solo di chi e' collegato, e i
     * permessi di un giocatore scollegato non sono interrogabili): la colonna e' quindi la COPIA
     * dell'ultimo valore visto, aggiornata all'ingresso e a ogni giro di {@link #tickOnline()}.
     *
     * @return {@code true} se il tetto e' effettivamente CAMBIATO (cosi' chi chiama al volo al cambio
     *         permesso puo' rivalutare l'overclaim solo quando serve), {@code false} se era gia' quello.
     */
    public boolean syncMaxPower(Player p, int eff) {
        PP pp = ensure(p.getUniqueId());
        if (pp.maxPower == eff) return false;
        pp.maxPower = eff;
        clamp(pp);
        save(p.getUniqueId());
        return true;
    }

    /**
     * Come {@link #syncMaxPower} ma per un giocatore <b>OFFLINE</b> (solo UUID, nessun {@link Player}):
     * la usa la reazione istantanea al cambio permesso di chi non e' collegato. Aggiorna SOLO chi ha gia'
     * una riga in cache (un account mai entrato non contribuisce a nessuna fazione); il tetto resta scritto
     * nel DB perche' entra nella somma del maxpower di fazione anche da offline.
     *
     * @return {@code true} se il tetto e' effettivamente CAMBIATO, {@code false} altrimenti.
     */
    public boolean syncMaxPowerOffline(UUID u, int eff) {
        PP pp = cache.get(u);
        if (pp == null || pp.maxPower == eff) return false;
        pp.maxPower = eff;
        clamp(pp);
        save(u);
        return true;
    }

    /** Il tetto (maxPower) imposto da una mappa di permessi letta via LuckPerms per un giocatore OFFLINE.
     *  Incapsula la stessa risoluzione usata dal giro periodico {@link #tickOffline()}. */
    public int maxPowerFrom(Map<String, Boolean> permissions) {
        return vantaggi(permissions).maxPower;
    }

    /** Zoom mappa salvato (colonna map_rows) come intero = round(bpp*100); 0 = default di config. */
    public int getMapZoom(UUID u) { PP pp = cache.get(u); return pp == null ? 0 : pp.mapRows; }

    /** (admin) Imposta lo zoom mappa del giocatore in BLOCCHI PER PIXEL (es. 0.25, 0.5, 1). 0 = default
     *  di config. Salvato come round(bpp*100) sulla colonna map_rows. */
    public void setMapZoomBpp(UUID u, double bpp) {
        PP pp = ensure(u);
        pp.mapRows = bpp <= 0 ? 0 : Math.max(1, Math.min(6400, (int) Math.round(bpp * 100))); // 0.01..64 bpp
        save(u);
    }

    /** Il giocatore ha DIRITTO alla minimap HUD (permesso {@link #PERM_MINIMAP}), a prescindere dal fatto
     *  che poi l'abbia spenta lui con /f minimap off. E' il gate "di sistema"; l'interruttore personale e'
     *  {@link #isMinimapHidden}. */
    public boolean hasMinimapPermission(Player p) { return p.hasPermission(PERM_MINIMAP); }

    /** Il giocatore ha SPENTO la minimap con /f minimap off (preferenza personale, colonna minimap_hidden). */
    public boolean isMinimapHidden(UUID u) { PP pp = cache.get(u); return pp != null && pp.minimapHidden; }

    /** La minimap HUD va mostrata a questo giocatore ADESSO: ha il permesso E non l'ha spenta lui. Lo usano
     *  join e riconciliazione periodica ({@link #tickOnline}) per montare/smontare l'HUD. */
    public boolean canUseMinimap(Player p) { return hasMinimapPermission(p) && !isMinimapHidden(p.getUniqueId()); }

    /**
     * Accende/spegne la minimap HUD del giocatore (comando /f minimap). Salva la preferenza e monta o smonta
     * subito l'HUD, senza aspettare la riconciliazione periodica. Non tocca il permesso: se il giocatore non
     * ha {@link #PERM_MINIMAP} la minimap resta comunque non disponibile.
     */
    public void setMinimapHidden(Player p, boolean hidden) {
        PP pp = ensure(p.getUniqueId());
        if (pp.minimapHidden == hidden) return;
        pp.minimapHidden = hidden;
        save(p.getUniqueId());
        if (minimapManager == null || !minimapManager.isAvailable()) return;
        if (canUseMinimap(p)) reattachMinimap(p);
        else if (minimapManager.isActive(p)) minimapManager.deactivate(p);
    }

    /**
     * Attiva la minimap HUD di chi ne ha il permesso (all'ingresso, dopo un /reload, o quando il
     * permesso gli viene dato mentre e' collegato): rimanda il resource pack e rispawna il quadro
     * fittizio con un tick di ritardo, cosi' ProtocolLib e il client hanno il tempo di essere pronti
     * (stesso principio del remount dopo teleport in {@code MinimapListener}). Non fa nulla se il
     * giocatore non ha il permesso o se ProtocolLib non e' disponibile.
     */
    public void reattachMinimap(Player p) {
        if (minimapManager == null || !minimapManager.isAvailable()) return;
        if (!canUseMinimap(p)) return;
        // Un'attivazione alla volta: la riconciliazione periodica di tickOnline() ripassa ogni pochi
        // secondi e senza questo segnaposto rischierebbe di accodare piu' attivazioni per lo stesso
        // giocatore mentre la prima e' ancora nei 60 tick di attesa.
        if (!minimapPending.add(p.getUniqueId())) return;
        // 60 tick (~3s) di ritardo: al PRIMO login dopo un riavvio il server sta gia' caricando i chunk
        // attorno al giocatore (spike di TPS inerente). Attivare la minimap subito ci sommava sopra il
        // costo del primo render (campionamento chunk + pixel). Ritardando, il caricamento chunk si
        // assesta prima e i due costi non si accavallano nel momento peggiore (l'HUD compare ~3s dopo).
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            minimapPending.remove(p.getUniqueId());
            if (!p.isOnline() || !canUseMinimap(p)) return;
            // Col pack OBBLIGATORIO l'ha gia' ricevuto al join (ResourcePackListener): rimandarlo qui
            // sarebbe un doppione che fa ripartire inutilmente il ciclo richiesta/esito sul client.
            if (resourcePack != null && resourcePack.isAvailable() && !resourcePack.isRequired()) resourcePack.sendTo(p);
            minimapManager.activate(p);
        }, 60L);
    }

    // ------------------------------ EVENTI -------------------------------
    /**
     * Ingresso: rilegge PRIMA la riga {@code players} dal database (async) — cosi' eventuali modifiche
     * fatte a mano al DB (o da un altro processo) mentre il giocatore era offline/il server acceso si
     * riflettono al rientro, invece di restare "invisibili" fino al prossimo riavvio (la cache in memoria
     * normalmente non si accorge di scritture esterne al plugin). Poi applica nome + decadimento offline
     * come prima, e se il giocatore ha gia' una Mappa Fazioni in mano/inventario ne aggiorna la scala nel
     * caso lo zoom sia stato cambiato nel frattempo (stesso meccanismo di {@code /mf admin setmap}).
     */
    public void onJoin(Player p) {
        UUID u = p.getUniqueId();
        dbExec.submit(() -> {
            PP fresh = null;
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT name, power, max_power, last_seen, map_rows, power_progress, last_login, minimap_hidden FROM players WHERE uuid=?")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        fresh = new PP(rs.getString("name"),
                                (int) Math.round(rs.getDouble("power")),
                                (int) Math.round(rs.getDouble("max_power")),
                                rs.getLong("last_seen"),
                                rs.getInt("map_rows"),
                                rs.getInt("power_progress"));
                        long ll = rs.getLong("last_login");
                        fresh.lastLogin = ll > 0 ? ll : fresh.lastSeen;
                        fresh.minimapHidden = rs.getInt("minimap_hidden") != 0;
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("[Power] rilettura al login: " + e.getMessage());
            }
            PP result = fresh;
            Bukkit.getScheduler().runTask(plugin, () -> applyJoin(p, result));
        });
    }

    /** Applica sul main thread l'esito della rilettura DB fatta da {@link #onJoin}. */
    private void applyJoin(Player p, PP fresh) {
        UUID u = p.getUniqueId();
        PP pp;
        if (fresh != null) {
            cache.put(u, fresh); // sostituisce la cache coi valori appena letti dal DB (rispecchia edit esterni)
            pp = fresh;
        } else {
            pp = ensure(u); // nessuna riga trovata: comportati come prima (nuovo giocatore)
        }
        pp.name = p.getName();
        long now = System.currentTimeMillis();
        Vantaggi vip = vantaggi(p);
        // Coda di decadimento: il giro offline (tickOffline) ha gia' consumato i periodi interi mentre era
        // via, qui si chiude quello eventualmente maturato dall'ultimo giro a questo istante.
        applyDecay(u, pp, now, vip.loss);
        // Il tetto lo dettano i permessi: un VIP scaduto (o appena promosso) ha il valore giusto gia' al
        // primo tick di gioco, senza aspettare la riconciliazione periodica.
        pp.maxPower = vip.maxPower;
        clamp(pp);
        pp.lastSeen = now;
        pp.lastLogin = now;   // accesso reale: timestamp pulito per l'inattivita' (non lo tocca il decadimento)
        save(u);
        if (mapService != null) mapService.updateScale(p, resolveZoomFactor(pp.mapRows), resolveDisplayName(pp.mapRows));
        reattachMinimap(p);
    }

    /** Blocchi per pixel effettivi dal valore salvato (0 -> default di config map.default-zoom). */
    private double resolveZoomFactor(int mapRows) {
        if (mapRows <= 0) return Math.max(0.05, plugin.getConfig().getDouble("map.default-zoom", DEFAULT_ZOOM));
        return mapRows / 100.0;
    }

    /** Blocchi per pixel dello zoom `/f map` del giocatore — usato dalla minimap HUD per mostrare SEMPRE
     *  la stessa area della sua mappa cartacea, mai un valore indipendente. */
    public double getResolvedZoomFactor(UUID u) {
        PP pp = cache.get(u);
        return resolveZoomFactor(pp == null ? 0 : pp.mapRows);
    }

    /** Nome dell'item mappa: formato config {@code map.item.name-format} con {zoom} sostituito dai bpp. */
    private String resolveDisplayName(int mapRows) {
        return com.teolo.magixfactions.map.MapService.itemName(plugin, resolveZoomFactor(mapRows));
    }

    public void onQuit(UUID u) {
        PP pp = cache.get(u);
        if (pp == null) return;
        long now = System.currentTimeMillis();
        pp.lastSeen = now;
        pp.lastLogin = now;   // ultimo momento in cui era davvero collegato
        save(u);
    }

    /** Ultimo accesso REALE del giocatore (join/quit), 0 se sconosciuto. Usato per capire da quanto una
     *  fazione e' inattiva (vedi ScoreManager). NON e' last_seen, che il decadimento fa avanzare. */
    public long lastLogin(UUID u) {
        PP pp = cache.get(u);
        return pp == null ? 0 : pp.lastLogin;
    }

    /** Morte: -death-loss (minimo -maxPower). Avvisa il giocatore (solo lui) di quanta Potenza ha perso
     *  e quanta gliene resta, se {@code power.death-message} e' attivo. */
    public void onDeath(Player p) {
        flushFaction(p.getUniqueId());   // accredita la potenza pre-morte fino a ora, poi cala (media esatta)
        PP pp = ensure(p.getUniqueId());
        int before = pp.power;
        pp.power -= deathLoss();
        clamp(pp);
        int lost = before - pp.power; // effettivo: vicino al minimo puo' essere < death-loss (anche 0)
        save(p.getUniqueId());
        if (lost > 0 && messages != null && plugin.getConfig().getBoolean("power.death-message", true)) {
            p.sendMessage(messages.prefix() + messages.get("power.death-loss",
                    "lost", String.valueOf(lost),
                    "power", String.valueOf(pp.power),
                    "maxpower", String.valueOf(pp.maxPower)));
        }
    }

    /**
     * Giro periodico sui giocatori online (ogni {@code power.tick-seconds}). Fa tre cose:
     * <ol>
     *   <li>riallinea tetto di Potenza e minimap a quello che dicono i PERMESSI in questo momento —
     *       cosi' un VIP appena promosso (o appena scaduto) non deve riconnettersi per vedere il
     *       cambiamento;</li>
     *   <li>fa avanzare il recupero della Potenza alla velocita' personale del giocatore.</li>
     * </ol>
     * L'avanzamento e' contato in "secondi x percentuale": ogni giro aggiunge
     * {@code tick-seconds x velocita'%} e a {@code gain-interval-seconds x 100} scatta il punto di
     * Potenza. Cosi' un giocatore normale (100%) ci mette esattamente i {@code gain-interval-seconds} di
     * prima, uno al 200% la meta' del tempo, e il conto non dipende dal momento in cui si e' collegato
     * (col vecchio timer unico chi entrava un attimo prima dello scoccare prendeva il punto in regalo).
     */
    public void tickOnline() {
        int gain = gainAmount();
        int threshold = gainIntervalSeconds() * 100; // "secondi x percentuale" per un punto di Potenza
        int tick = tickSeconds();
        boolean minimapUp = minimapManager != null && minimapManager.isAvailable();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID u = p.getUniqueId();
            Vantaggi vip = vantaggi(p);          // un solo giro sui permessi: tetto + velocita'
            syncMaxPower(p, vip.maxPower);
            if (minimapUp) {
                boolean puo = canUseMinimap(p);
                if (puo && !minimapManager.isActive(p)) reattachMinimap(p);
                else if (!puo && minimapManager.isActive(p)) minimapManager.deactivate(p);
            }
            PP pp = ensure(u);
            if (pp.power >= pp.maxPower) {          // al tetto: niente da recuperare, riparti da zero
                if (pp.progress != 0) { pp.progress = 0; save(u); }
                continue;
            }
            pp.progress += tick * Math.max(0, vip.speed);
            if (pp.progress < threshold) continue;     // ancora in mezzo al giro: nessuna scrittura
            int points = pp.progress / threshold;
            pp.progress -= points * threshold;
            flushFaction(u);   // accredita la potenza vecchia fino a ora, poi sale (media esatta)
            pp.power = Math.min(pp.maxPower, pp.power + gain * points);
            if (pp.power >= pp.maxPower) pp.progress = 0;
            save(u);
        }
    }

    public void saveAllOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) onQuit(p.getUniqueId());
    }

    /**
     * Giro periodico sui giocatori <b>OFFLINE</b> (ogni {@code power.offline-refresh-minutes}), il
     * gemello di {@link #tickOnline()} per chi non e' collegato. Fa due cose che prima potevano avvenire
     * solo al rientro del giocatore:
     * <ol>
     *   <li><b>riallinea il tetto</b> di Potenza ai permessi. Serve perche' il maxpower di FAZIONE e' la
     *       somma dei tetti di tutti i membri: senza questo giro, un VIP scaduto su un account che non
     *       rientra piu' continuerebbe a regalare tetto alla sua fazione per sempre;</li>
     *   <li><b>applica il decadimento da offline</b> alla velocita' personale
     *       ({@code magixfactions.power.speed.loss.<%>}), cosi' la Potenza di una fazione di assenti cala
     *       in tempo reale — e la fazione diventa raidabile quando deve, non al loro prossimo login.</li>
     * </ol>
     * I permessi di chi e' offline li sa solo LuckPerms e leggerli tocca il suo storage: la lettura sta
     * tutta in un task ASINCRONO, e sul main thread torna solo l'applicazione dei valori. Senza
     * LuckPerms il giro continua a funzionare col tetto gia' in cache e la velocita' normale.
     */
    public void tickOffline() {
        if (cache.isEmpty()) return;
        // Fotografia sul main thread: chi e' offline e ha una riga. (La cache non si tocca da altri thread.)
        final java.util.List<UUID> candidati = new java.util.ArrayList<>();
        for (UUID u : cache.keySet()) {
            if (Bukkit.getPlayer(u) == null) candidati.add(u);
        }
        if (candidati.isEmpty()) return;
        final boolean lpPronto = luckPerms != null && luckPerms.available();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, Vantaggi> letti = new HashMap<>();
            for (UUID u : candidati) {
                Map<String, Boolean> permissions = lpPronto ? luckPerms.permissions(u) : null;
                if (permissions != null) letti.put(u, vantaggi(permissions));
            }
            Bukkit.getScheduler().runTask(plugin, () -> applyOfflinePass(candidati, letti));
        });
    }

    /** Applica sul main thread l'esito del giro offline: tetto dai permessi + decadimento maturato. */
    private void applyOfflinePass(java.util.List<UUID> candidati, Map<UUID, Vantaggi> letti) {
        long now = System.currentTimeMillis();
        for (UUID u : candidati) {
            if (Bukkit.getPlayer(u) != null) continue; // e' rientrato nel frattempo: ci pensa applyJoin
            PP pp = cache.get(u);
            if (pp == null) continue;
            Vantaggi vip = letti.get(u); // null = LuckPerms assente o lettura fallita
            boolean cambiato = false;
            if (vip != null && pp.maxPower != vip.maxPower) {
                pp.maxPower = vip.maxPower;
                clamp(pp);
                cambiato = true;
            }
            if (applyDecay(u, pp, now, vip == null ? 100 : vip.loss) > 0) cambiato = true;
            if (cambiato) save(u);
        }
    }

    // ------------------------------ HELPER -------------------------------
    /** Ultimo valore ignoto gia' segnalato: l'avviso va dato una volta, non a ogni giro. */
    private String periodoIgnotoAvvisato;

    /**
     * Durata del periodo di perdita per un giocatore normale ({@code power.offline-decay.per}):
     * {@code hour | day | week | month} (il mese vale 30 giorni).
     * <p>
     * Un valore non riconosciuto (un refuso, o "settimana" scritto in italiano) prima finiva zitto
     * sull'ORA: la Potenza calava 24 volte piu' in fretta di quanto l'amministratore credeva, e nel log
     * non compariva niente. Ora si ripiega sul GIORNO — il default documentato — e lo si dice in console.
     */
    private long unitaDecadimentoMs() {
        String per = plugin.getConfig().getString("power.offline-decay.per", "day").toLowerCase(Locale.ROOT);
        return switch (per) {
            case "hour" -> 3_600_000L;
            case "day" -> 86_400_000L;
            case "week" -> 604_800_000L;
            case "month" -> 2_592_000_000L; // 30 giorni
            default -> {
                if (!per.equals(periodoIgnotoAvvisato)) {
                    periodoIgnotoAvvisato = per;
                    plugin.getLogger().warning("[Potenza] power.offline-decay.per = \"" + per + "\" non e' un valore "
                            + "valido (hour | day | week | month): uso GIORNO. Correggi il config.yml.");
                }
                yield 86_400_000L;
            }
        };
    }

    /**
     * Applica il decadimento da offline maturato fra {@code pp.lastSeen} e {@code now}, alla velocita'
     * personale {@code lossPercent} (100 = normale, 50 = ci mette il doppio, 200 = perde il doppio).
     * <p>
     * Il periodo effettivo e' {@code unita' x 100 / lossPercent}: con perdita di 1 al giorno, 50 diventa
     * uno ogni 48 ore e 200 uno ogni 12 (cioe' due al giorno). Avanza {@code lastSeen} dei soli periodi
     * <b>interi</b> consumati, cosi' chiamarlo spesso o di rado da' lo stesso risultato e il tempo di
     * troppo non viene buttato via a ogni giro.
     *
     * @return quanta Potenza e' stata tolta (0 se niente).
     */
    private int applyDecay(UUID u, PP pp, long now, int lossPercent) {
        int amount = plugin.getConfig().getInt("power.offline-decay.amount", 0);
        if (amount <= 0 || lossPercent <= 0 || pp.lastSeen <= 0 || now <= pp.lastSeen) return 0;
        long periodoMs = Math.max(1L, unitaDecadimentoMs() * 100 / lossPercent);
        long periodi = (now - pp.lastSeen) / periodoMs;
        if (periodi <= 0) return 0;
        flushFaction(u);   // accredita la potenza vecchia fino a ora, poi cala per il decadimento (media esatta)
        int prima = pp.power;
        pp.power -= (int) Math.min(periodi * amount, Integer.MAX_VALUE);
        clamp(pp);
        pp.lastSeen += periodi * periodoMs;
        return prima - pp.power;
    }

    private void clamp(PP pp) {
        if (pp.power > pp.maxPower) pp.power = pp.maxPower;
        if (pp.power < -pp.maxPower) pp.power = -pp.maxPower;
    }

    private void save(UUID u) {
        PP pp = cache.get(u);
        if (pp == null) return;
        // Snapshot dei valori sul main thread; l'UPDATE viene eseguito in async serializzato.
        final String us = u.toString(), name = pp.name;
        final int power = pp.power, maxPower = pp.maxPower, mapRows = pp.mapRows, progress = pp.progress;
        final long lastSeen = pp.lastSeen, lastLogin = pp.lastLogin;
        final int minimapHidden = pp.minimapHidden ? 1 : 0;
        dbExec.submit(() -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE players SET name=?, power=?, max_power=?, last_seen=?, map_rows=?, power_progress=?, last_login=?, minimap_hidden=? WHERE uuid=?")) {
                ps.setString(1, name); ps.setDouble(2, power); ps.setDouble(3, maxPower);
                ps.setLong(4, lastSeen); ps.setInt(5, mapRows); ps.setInt(6, progress); ps.setLong(7, lastLogin);
                ps.setInt(8, minimapHidden); ps.setString(9, us);
                ps.executeUpdate();
            } catch (SQLException e) { plugin.getLogger().warning("[Power] salvataggio: " + e.getMessage()); }
        });
    }
}
