package com.teolo.magixmenus.menu;

import com.teolo.magixmenus.actions.Action;
import com.teolo.magixmenus.requirements.Requirements;
import com.teolo.magixmenus.requirements.Requirement;
import com.teolo.magixmenus.util.Slot;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Da un file .yml a un {@link MenuDef}.
 *
 * <h2>Non si lancia mai niente</h2>
 * Ogni cosa che non si capisce diventa una riga in {@link MenuDef#errori()} e il caricamento
 * prosegue. Un menu con un item sbagliato si apre con quell'item rotto e tutti gli altri al loro
 * posto; un menu con il titolo sbagliato si apre senza titolo. La regola vale anche per il file
 * intero: se e' YAML non valido si ottiene un menu vuoto con scritto perche', non un plugin che
 * si rifiuta di partire trascinandosi dietro tutti gli altri menu.
 *
 * <h2>I nomi delle chiavi</h2>
 * Le chiavi si scrivono in <b>inglese</b> ({@code slot}, {@code display_name}, {@code lore},
 * {@code show_requirements}, {@code actions}): e' la regola comune a tutti i plugin Magix, ed e'
 * anche cio' che permette di incollare un menu preso da una guida di un altro plugin senza
 * tradurlo riga per riga.
 *
 * I vecchi nomi italiani ({@code nome}, {@code descrizione}, {@code mostra_se}, {@code azioni},
 * {@code prezzo}...) restano accettati come <b>sinonimi</b>, per sempre: i menu gia' scritti non
 * si toccano. Quando le due forme sono presenti entrambe vince quella inglese, che e' quella che
 * scrivono il plugin e l'editor del sito.
 */
public final class MenuLoader {

    private MenuLoader() {
    }

    public static MenuDef daFile(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT).endsWith(".yml")
                ? file.getName().substring(0, file.getName().length() - 4)
                : file.getName();
        List<String> errori = new ArrayList<>();

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            errori.add("il file non e' YAML valido: " + e.getMessage());
            return vuoto(name, errori);
        }
        return da(name, yaml, errori);
    }

    static MenuDef da(String name, ConfigurationSection root, List<String> errori) {
        ConfigurationSection m = root.getConfigurationSection("menu");
        if (m == null) {
            // Anche senza il blocco "menu:" il file si legge: le chiavi si cercano in cima.
            m = root;
        }

        MenuType type = MenuType.read(textWith(m, "chest", "type", "tipo"));
        if (type == null) {
            errori.add("tipo di menu sconosciuto: \"" + text(m, "type", "tipo") + "\" (validi: "
                    + String.join(", ", MenuType.names()) + "). Uso il baule.");
            type = MenuType.CHEST;
        }

        int rows = interoCon(m, 3, "rows", "righe");
        if (type.customRows() && (rows < 1 || rows > 6)) {
            errori.add("un baule ha da 1 a 6 righe, non " + rows + ". Uso 3.");
            rows = 3;
        }
        if (!type.customRows() && (m.isSet("rows") || m.isSet("righe"))) {
            errori.add("il tipo " + type.name().toLowerCase(Locale.ROOT)
                    + " ha una forma fissa: la chiave rows non ha effetto.");
        }

        int dimensione = type.dimensione(rows);
        int larghezza = type.larghezza();

        String title = textWith(m, "", "title", "titolo");
        int aggiornamento = interoCon(m, 0, "update", "aggiornamento");
        if (aggiornamento < 0) {
            errori.add("aggiornamento negativo: lo tratto come 0 (nessun aggiornamento).");
            aggiornamento = 0;
        }

        List<String> commands = new ArrayList<>();
        for (String c : textList(m, "commands", "command", "comandi", "comando")) {
            String clean = c.trim().toLowerCase(Locale.ROOT);
            if (clean.startsWith("/")) {
                clean = clean.substring(1);
            }
            if (!clean.isEmpty()) {
                commands.add(clean);
            }
        }

        String permesso = text(m, "permission", "permesso");
        List<String> arguments = textList(m, "arguments", "args", "argomenti");

        Requirements openIf = requirements(m, errori, "open_requirements", "open_requirement", "apri_se", "apri_requisiti");
        List<Action> apertura = actions(m, errori, "open_actions", "azioni_apertura");
        List<Action> chiusura = actions(m, errori, "close_actions", "azioni_chiusura");
        boolean freeClose = booleanoCon(m, true, "closeable", "chiusura_libera");

        // --- gli item ---
        List<ItemDef> item = new ArrayList<>();
        ConfigurationSection itemSection = firstSection(root, "items", "item", "oggetti");
        if (itemSection != null) {
            for (String key : itemSection.getKeys(false)) {
                ConfigurationSection s = itemSection.getConfigurationSection(key);
                if (s == null) {
                    errori.add("l'item \"" + key + "\" non e' un blocco di impostazioni.");
                    continue;
                }
                ItemDef def = item(key, s, larghezza, dimensione, errori, true);
                if (def != null) {
                    item.add(def);
                }
            }
        }

        // --- il contenuto che si genera da solo ---
        Content content = null;
        ConfigurationSection contentSection = firstSection(root, "content", "contenuto", "elenco");
        if (contentSection != null) {
            content = content(contentSection, larghezza, dimensione, errori);
        }

        // --- la parte da finestra di dialogo ---
        MenuDialog dialog = type.dialog() ? dialog(m, root, errori) : null;

        if (item.isEmpty() && content == null && !type.dialog()) {
            errori.add("questo menu non ha nessun item: si aprira' vuoto.");
        }

        return new MenuDef(name, type, rows, title, aggiornamento, commands, permesso, arguments,
                openIf, apertura, chiusura, item, content, dialog, freeClose, errori);
    }

    static MenuDef vuoto(String name, List<String> errori) {
        return new MenuDef(name, MenuType.CHEST, 3, "&cMenu con errori", 0, List.of(), null,
                List.of(), Requirements.NESSUNO, List.of(), List.of(), List.of(), null, null, true, errori);
    }

    // ----------------------------------------------------------------- dialogo

    private static MenuDialog dialog(ConfigurationSection m, ConfigurationSection root,
                                   List<String> errori) {
        // Le chiavi del dialogo si accettano sia dentro "menu:" sia in cima al file: sono la
        // sostanza di questo menu, e discutere di dove metterle e' tempo perso per chi lo scrive.
        List<String> body = textList(m, "body", "corpo", "testo");
        if (body.isEmpty()) {
            body = textList(root, "body", "corpo", "testo");
        }

        List<MenuDialog.Field> fields = new ArrayList<>();
        ConfigurationSection fieldsSection = firstSection(m, "inputs", "campi");
        if (fieldsSection == null) {
            fieldsSection = firstSection(root, "inputs", "campi");
        }
        if (fieldsSection != null) {
            for (String key : fieldsSection.getKeys(false)) {
                ConfigurationSection s = fieldsSection.getConfigurationSection(key);
                if (s == null) {
                    errori.add("il campo \"" + key + "\" non e' un blocco di impostazioni.");
                    continue;
                }
                MenuDialog.FieldType fieldType = MenuDialog.FieldType.read(textWith(s, "text", "type", "tipo"));
                if (fieldType == null) {
                    errori.add("campo \"" + key + "\": tipo sconosciuto \"" + text(s, "type", "tipo")
                            + "\" (validi: testo, booleano, numero, scelta).");
                    continue;
                }
                List<String> opzioni = textList(s, "opzioni", "options", "valori");
                if (fieldType == MenuDialog.FieldType.CHOICE && opzioni.isEmpty()) {
                    errori.add("campo \"" + key + "\": una scelta senza opzioni non si puo' fare.");
                    continue;
                }
                fields.add(new MenuDialog.Field(key, fieldType,
                        textWith(s, key, "label", "etichetta"),
                        textWith(s, "", "default", "iniziale"),
                        (float) s.getDouble("da", s.getDouble("min", 0)),
                        (float) s.getDouble("a", s.getDouble("max", 100)),
                        (float) s.getDouble("passo", s.getDouble("step", 1)),
                        interoCon(s, 32, "max_length", "lunghezza"),
                        interoCon(s, 200, "width", "larghezza"),
                        booleanoCon(s, false, "multiline", "piu_righe"),
                        opzioni));
            }
        }

        List<MenuDialog.Bottone> bottoni = new ArrayList<>();
        Object rawButtons = primo(m, "buttons", "bottoni");
        if (rawButtons == null) {
            rawButtons = primo(root, "buttons", "bottoni");
        }
        if (rawButtons instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> b = mappa(o);
                if (b == null) {
                    errori.add("un bottone non e' scritto come un blocco: lo salto.");
                    continue;
                }
                YamlConfiguration finto = new YamlConfiguration();
                for (Map.Entry<String, Object> e : b.entrySet()) {
                    metti(finto, e.getKey(), e.getValue());
                }
                String label = text(b, "label", "etichetta", "nome", "testo");
                if (label == null) {
                    errori.add("un bottone non ha l'etichetta: lo salto.");
                    continue;
                }
                bottoni.add(new MenuDialog.Bottone(label,
                        text(b, "tooltip", "suggerimento", "descrizione"),
                        primo(b, "width", "larghezza") instanceof Number n ? n.intValue() : 150,
                        requirements(finto, errori, "show_requirements", "mostra_se"),
                        actions(finto, errori, "actions", "azioni")));
            }
        }

        if (bottoni.isEmpty()) {
            // Senza bottoni resta solo la crocetta per chiudere: e' un avviso, non un errore,
            // e per un messaggio di sola lettura va benissimo.
            errori.add("dialogo senza bottoni: si potra' solo leggere e chiudere.");
        }
        boolean paused = booleanoCon(m, booleanoCon(root, false, "pause", "pausa"), "pause", "pausa");
        return new MenuDialog(body, fields, bottoni, paused);
    }

    // ------------------------------------------------------------------- item

    /**
     * @param conCaselle falso per l'item modello di un contenuto: quello non ha caselle proprie,
     *                   le prende dall'elenco che lo ospita.
     */
    private static ItemDef item(String key, ConfigurationSection s, int larghezza, int dimensione,
                                List<String> errori, boolean withSlots) {
        ItemDef def = new ItemDef();
        def.name(key);

        if (withSlots) {
            Object slot = primo(s, "slot", "slots", "casella", "caselle");
            if (slot == null) {
                errori.add("l'item \"" + key + "\" non dice in quale casella va (chiave slot).");
                return null;
            }
            List<String> problemiSlot = new ArrayList<>();
            List<Integer> slots = Slot.read(slot, larghezza, dimensione, problemiSlot);
            for (String p : problemiSlot) {
                errori.add("item \"" + key + "\": " + p);
            }
            if (slots.isEmpty()) {
                errori.add("l'item \"" + key + "\" non finisce in nessuna casella valida: lo salto.");
                return null;
            }
            def.slots(slots);
        }

        String materiale = text(s, "id", "material", "materiale", "item", "tipo");
        if (materiale == null && text(s, "head", "testa", "skull") == null) {
            errori.add("l'item \"" + key + "\" non dice che item e' (chiave id).");
            return null;
        }
        def.materiale(materiale == null ? "PLAYER_HEAD" : materiale);

        Object quantita = primo(s, "amount", "quantity", "quantita");
        if (quantita != null) {
            def.quantita(String.valueOf(quantita));
        }

        String displayName = text(s, "display_name", "name", "nome", "titolo");
        if (displayName != null) {
            def.title(displayName);
        }
        def.description(textList(s, "lore", "descrizione", "testo"));
        def.incantesimi(textList(s, "enchantments", "enchants", "incantesimi"));
        def.luccica(booleanoCon(s, false, "glow", "luccica"));
        def.indistruttibile(booleanoCon(s, false, "unbreakable", "indistruttibile"));
        def.hideDetails(booleanoCon(s, false, "hide_details", "hide_attributes", "hide_all",
                "nascondi_dettagli"));

        Object modello = primo(s, "custom_model_data", "modello_custom", "modello");
        if (modello != null) {
            def.modelloCustom(String.valueOf(modello));
        }
        String modelloItem = text(s, "item_model", "modello_item");
        if (modelloItem != null) {
            def.modelloItem(modelloItem);
        }
        String color = text(s, "color", "colore");
        if (color != null) {
            def.color(color);
        }
        String testa = text(s, "head", "testa", "skull", "owner");
        if (testa != null) {
            def.testa(testa);
        }
        String raw = text(s, "components", "nbt", "custom_nbt", "avanzate", "componenti");
        if (raw != null) {
            def.raw(raw);
        }

        String prezzo = text(s, "price", "prezzo", "costo");
        if (prezzo != null) {
            def.prezzo(prezzo);
        }
        String dai = text(s, "give", "dai", "merce", "articolo");
        if (dai != null) {
            def.dai(dai);
        }
        String vendi = text(s, "sell", "vendi", "prezzo_vendita");
        if (vendi != null) {
            def.vendi(vendi);
        }
        if (vendi != null && dai == null) {
            errori.add("l'item \"" + key + "\" si puo' vendere ma non dice cosa: aggiungi la chiave give.");
        }

        def.showIf(requirements(s, errori, "show_requirements", "mostra_se", "mostra_requisiti"));
        for (Click c : Click.values()) {
            Requirements r = requirements(s, errori, c.requirementsKey(), c.requirementsKeyItalian());
            if (!r.vuoto()) {
                def.clickIf(c, r);
            }
            List<Action> a = actions(s, errori, c.actionsKey(), c.actionsKeyItalian());
            if (!a.isEmpty()) {
                def.actions(c, a);
            }
        }
        def.clickDelay(interoCon(s, 0, "cooldown", "attesa_fra_clic"));

        def.computeIfDynamic();
        return def;
    }

    private static Content content(ConfigurationSection s, int larghezza, int dimensione,
                                       List<String> errori) {
        Content.Fonte fonte = Content.Fonte.read(text(s, "source", "fonte"));
        if (fonte == null) {
            errori.add("contenuto: fonte sconosciuta \"" + text(s, "source", "fonte")
                    + "\" (valide: giocatori_online, lista, placeholder). Salto il contenuto.");
            return null;
        }

        List<String> problemiSlot = new ArrayList<>();
        List<Integer> slots = Slot.read(primo(s, "slot", "slots", "caselle"),
                larghezza, dimensione, problemiSlot);
        for (String p : problemiSlot) {
            errori.add("contenuto: " + p);
        }
        if (slots.isEmpty()) {
            errori.add("contenuto: nessuna casella valida in cui mettere le voci. Salto il contenuto.");
            return null;
        }

        ConfigurationSection entrySection = firstSection(s, "entry", "voce", "modello", "item");
        if (entrySection == null) {
            errori.add("contenuto: manca il blocco \"entry\" che dice com'e' fatta una voce.");
            return null;
        }
        ItemDef entry = item("voce", entrySection, larghezza, dimensione, errori, false);
        if (entry == null) {
            return null;
        }

        return new Content(fonte, textWith(s, "", "placeholder", "parametro"),
                textList(s, "list", "lista"), textWith(s, ",", "separator", "separatore"),
                slots, entry);
    }

    // ------------------------------------------------------------- requisiti

    private static Requirements requirements(ConfigurationSection padre, List<String> errori, String... keys) {
        Object raw = primo(padre, keys);
        if (raw == null) {
            return Requirements.NESSUNO;
        }

        List<Requirement> elenco = new ArrayList<>();
        int minimum = 0;
        List<Action> negate = List.of();

        if (raw instanceof List<?> list) {
            for (Object o : list) {
                Requirement r = requirement(mappa(o), errori);
                if (r != null) {
                    elenco.add(r);
                }
            }
            return new Requirements(elenco, 0, negate);
        }

        ConfigurationSection s = firstSection(padre, keys);
        if (s == null) {
            errori.add("il blocco " + keys[0] + " non e' scritto come mi aspetto: lo ignoro.");
            return Requirements.NESSUNO;
        }
        minimum = interoCon(s, 0, "minimum", "minimum_requirements", "minimo");
        negate = actions(s, errori, "deny_actions", "deny_commands", "azioni_negate");

        ConfigurationSection inside = firstSection(s, "requirements", "requisiti");
        ConfigurationSection where = inside != null ? inside : s;
        for (String k : where.getKeys(false)) {
            if (where == s && SERVICE_KEYS.contains(k)) {
                continue;
            }
            ConfigurationSection uno = where.getConfigurationSection(k);
            if (uno == null) {
                errori.add("il requisito \"" + k + "\" non e' un blocco di impostazioni.");
                continue;
            }
            Requirement r = requirement(uno.getValues(false), errori);
            if (r != null) {
                elenco.add(r);
            }
        }
        return new Requirements(elenco, minimum, negate);
    }

    private static Requirement requirement(Map<String, Object> values, List<String> errori) {
        if (values == null) {
            return null;
        }
        Object writtenType = values.get("type") != null ? values.get("type") : values.get("tipo");
        Requirement.Type type = Requirement.Type.read(writtenType == null ? null : String.valueOf(writtenType));
        if (type == null) {
            errori.add("requisito di tipo sconosciuto: \"" + writtenType + "\" (validi: "
                    + String.join(", ", Requirement.availableTypes()) + ").");
            return null;
        }
        String key = text(values, "key", "chiave", "input", "placeholder", "permission", "permesso");
        String value = text(values, "value", "valore", "output", "nome");
        Object quantita = primo(values, "amount", "quantity", "quantita", "importo");
        Object equal = primo(values, "match", "uguale", "risultato");

        int q = 1;
        if (quantita != null) {
            Double n = com.teolo.magixmenus.util.Text.number(String.valueOf(quantita));
            if (n == null) {
                errori.add("requisito " + type + ": la quantita' \"" + quantita + "\" non e' un numero.");
            } else {
                q = (int) (double) n;
            }
        }
        if (key == null && (type == Requirement.Type.PERMESSO || type == Requirement.Type.EQUATION)) {
            errori.add("requisito " + type + ": manca la chiave.");
            return null;
        }
        return new Requirement(type, key == null ? "" : key, value == null ? "" : value, q,
                equal == null || Boolean.parseBoolean(String.valueOf(equal)));
    }

    // ---------------------------------------------------------------- azioni

    private static List<Action> actions(ConfigurationSection padre, List<String> errori, String... keys) {
        Object raw = primo(padre, keys);
        if (raw == null) {
            return List.of();
        }
        List<Action> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                Action a = action(o, errori);
                if (a != null) {
                    out.add(a);
                }
            }
        } else {
            Action a = action(raw, errori);
            if (a != null) {
                out.add(a);
            }
        }
        return out;
    }

    private static Action action(Object o, List<String> errori) {
        if (o == null) {
            return null;
        }

        Map<String, Object> mappa = mappa(o);
        if (mappa != null) {
            // Blocco condizionale: se / allora / altrimenti
            Object condizione = mappa.get("if") != null ? mappa.get("if") : mappa.get("se");
            if (condizione == null) {
                errori.add("azione a blocco senza la chiave \"if\": la salto.");
                return null;
            }
            Requirements requirements;
            if (condizione instanceof String text) {
                // La forma corta: "if: %saldo% >= 100" e' un'equazione, il caso che capita sempre.
                requirements = new Requirements(List.of(new Requirement(Requirement.Type.EQUATION,
                        text, "", 1, true)), 0, List.of());
            } else {
                requirements = requirements(fakeSection("if", condizione), errori, "if");
            }
            List<Action> allora = subActions(mappa.get("then") != null ? mappa.get("then") : mappa.get("allora"), errori);
            List<Action> altrimenti = subActions(mappa.get("else") != null ? mappa.get("else") : mappa.get("altrimenti"), errori);
            return new Action(Action.Type.SE, "", requirements, allora, altrimenti);
        }

        String row = String.valueOf(o).trim();
        if (row.isEmpty()) {
            return null;
        }
        int colon = row.indexOf(':');
        String typeName = colon < 0 ? row : row.substring(0, colon);
        String argomento = colon < 0 ? "" : row.substring(colon + 1).trim();

        Action.Type type = Action.Type.read(typeName);
        if (type == null) {
            errori.add("azione sconosciuta: \"" + row + "\" (tipi validi: "
                    + String.join(", ", Action.availableTypes()) + ").");
            return null;
        }
        if (type.vuoleArgomento() && argomento.isEmpty()) {
            errori.add("l'azione \"" + typeName + "\" vuole qualcosa dopo i due punti.");
            return null;
        }
        return Action.di(type, argomento);
    }

    private static List<Action> subActions(Object o, List<String> errori) {
        if (o == null) {
            return List.of();
        }
        List<Action> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                Action a = action(x, errori);
                if (a != null) {
                    out.add(a);
                }
            }
        } else {
            Action a = action(o, errori);
            if (a != null) {
                out.add(a);
            }
        }
        return out;
    }

    // ------------------------------------------------------- lettura generica

    /**
     * Un pezzo di configurazione costruito al volo, per poter rileggere con gli stessi metodi un
     * blocco che e' arrivato come mappa invece che come sezione.
     *
     * Serve perche' dentro una LISTA (le azioni, i bottoni) YAML consegna delle mappe, non delle
     * sezioni: senza questo passaggio un {@code mostra_se} scritto dentro un bottone non verrebbe
     * riconosciuto, e sarebbe uno di quegli errori che non danno nessun messaggio.
     */
    private static ConfigurationSection fakeSection(String key, Object value) {
        YamlConfiguration finta = new YamlConfiguration();
        metti(finta, key, value);
        return finta;
    }

    private static void metti(ConfigurationSection where, String key, Object value) {
        Map<String, Object> m = mappa(value);
        if (m != null) {
            where.createSection(key, m);
        } else {
            where.set(key, value);
        }
    }

    /**
     * Le chiavi che dentro un blocco di requisiti NON sono un requisito.
     *
     * Sta qui e non sparsa in un "if" perche' ogni nome nuovo va aggiunto in un posto solo: la
     * volta che ci si dimentica, quella chiave viene letta come se fosse una condizione e il
     * menu si comporta in un modo che non si spiega guardando il file.
     */
    private static final java.util.Set<String> SERVICE_KEYS = java.util.Set.of(
            "minimum", "minimum_requirements", "minimo",
            "deny_actions", "deny_commands", "azioni_negate");

    /** Come {@link #testo} ma con un valore di ripiego se nessuna delle chiavi c'e'. */
    private static String textWith(ConfigurationSection s, String fallback, String... keys) {
        String v = text(s, keys);
        return v == null ? fallback : v;
    }

    private static int interoCon(ConfigurationSection s, int fallback, String... keys) {
        Object o = primo(s, keys);
        if (o instanceof Number n) {
            return n.intValue();
        }
        Double d = o == null ? null : com.teolo.magixmenus.util.Text.number(String.valueOf(o));
        return d == null ? fallback : (int) (double) d;
    }

    private static boolean booleanoCon(ConfigurationSection s, boolean fallback, String... keys) {
        Object o = primo(s, keys);
        if (o instanceof Boolean b) {
            return b;
        }
        return o == null ? fallback : Boolean.parseBoolean(String.valueOf(o));
    }

    /** Il valore della prima chiave che esiste, fra quelle passate (le altre sono i sinonimi). */
    private static Object primo(ConfigurationSection s, String... keys) {
        for (String k : keys) {
            if (k != null && s.isSet(k)) {
                return s.get(k);
            }
        }
        return null;
    }

    private static Object primo(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (k != null && m.containsKey(k)) {
                return m.get(k);
            }
        }
        return null;
    }

    private static ConfigurationSection firstSection(ConfigurationSection s, String... keys) {
        for (String k : keys) {
            if (k != null && s.isConfigurationSection(k)) {
                return s.getConfigurationSection(k);
            }
        }
        return null;
    }

    private static String text(ConfigurationSection s, String... keys) {
        Object o = primo(s, keys);
        return o == null ? null : String.valueOf(o);
    }

    private static String text(Map<String, Object> m, String... keys) {
        Object o = primo(m, keys);
        return o == null ? null : String.valueOf(o);
    }

    /**
     * Le liste di testi si scrivono sia come elenco sia come riga sola: {@code lore: "una riga"}
     * e {@code lore: ["una", "due"]} sono tutte e due valide, perche' pretendere le parentesi
     * quadre per una riga sola e' l'errore che si fa piu' spesso.
     */
    private static List<String> textList(ConfigurationSection s, String... keys) {
        Object o = primo(s, keys);
        if (o == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                out.add(String.valueOf(x));
            }
        } else {
            out.add(String.valueOf(o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mappa(Object o) {
        if (o instanceof ConfigurationSection s) {
            return s.getValues(false);
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return null;
    }
}
