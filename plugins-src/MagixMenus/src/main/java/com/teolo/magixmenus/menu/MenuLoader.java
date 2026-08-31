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
        String nome = file.getName().toLowerCase(Locale.ROOT).endsWith(".yml")
                ? file.getName().substring(0, file.getName().length() - 4)
                : file.getName();
        List<String> errori = new ArrayList<>();

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (Exception e) {
            errori.add("il file non e' YAML valido: " + e.getMessage());
            return vuoto(nome, errori);
        }
        return da(nome, yaml, errori);
    }

    static MenuDef da(String nome, ConfigurationSection radice, List<String> errori) {
        ConfigurationSection m = radice.getConfigurationSection("menu");
        if (m == null) {
            // Anche senza il blocco "menu:" il file si legge: le chiavi si cercano in cima.
            m = radice;
        }

        MenuType tipo = MenuType.leggi(testoCon(m, "chest", "type", "tipo"));
        if (tipo == null) {
            errori.add("tipo di menu sconosciuto: \"" + testo(m, "type", "tipo") + "\" (validi: "
                    + String.join(", ", MenuType.nomi()) + "). Uso il baule.");
            tipo = MenuType.CHEST;
        }

        int righe = interoCon(m, 3, "rows", "righe");
        if (tipo.righeSuMisura() && (righe < 1 || righe > 6)) {
            errori.add("un baule ha da 1 a 6 righe, non " + righe + ". Uso 3.");
            righe = 3;
        }
        if (!tipo.righeSuMisura() && (m.isSet("rows") || m.isSet("righe"))) {
            errori.add("il tipo " + tipo.name().toLowerCase(Locale.ROOT)
                    + " ha una forma fissa: la chiave rows non ha effetto.");
        }

        int dimensione = tipo.dimensione(righe);
        int larghezza = tipo.larghezza();

        String titolo = testoCon(m, "", "title", "titolo");
        int aggiornamento = interoCon(m, 0, "update", "aggiornamento");
        if (aggiornamento < 0) {
            errori.add("aggiornamento negativo: lo tratto come 0 (nessun aggiornamento).");
            aggiornamento = 0;
        }

        List<String> comandi = new ArrayList<>();
        for (String c : elencoDiTesti(m, "commands", "command", "comandi", "comando")) {
            String pulito = c.trim().toLowerCase(Locale.ROOT);
            if (pulito.startsWith("/")) {
                pulito = pulito.substring(1);
            }
            if (!pulito.isEmpty()) {
                comandi.add(pulito);
            }
        }

        String permesso = testo(m, "permission", "permesso");
        List<String> argomenti = elencoDiTesti(m, "arguments", "args", "argomenti");

        Requirements apriSe = requisiti(m, errori, "open_requirements", "open_requirement", "apri_se", "apri_requisiti");
        List<Action> apertura = azioni(m, errori, "open_actions", "azioni_apertura");
        List<Action> chiusura = azioni(m, errori, "close_actions", "azioni_chiusura");
        boolean chiusuraLibera = booleanoCon(m, true, "closeable", "chiusura_libera");

        // --- gli item ---
        List<ItemDef> item = new ArrayList<>();
        ConfigurationSection sezioneItem = primaSezione(radice, "items", "item", "oggetti");
        if (sezioneItem != null) {
            for (String chiave : sezioneItem.getKeys(false)) {
                ConfigurationSection s = sezioneItem.getConfigurationSection(chiave);
                if (s == null) {
                    errori.add("l'item \"" + chiave + "\" non e' un blocco di impostazioni.");
                    continue;
                }
                ItemDef def = item(chiave, s, larghezza, dimensione, errori, true);
                if (def != null) {
                    item.add(def);
                }
            }
        }

        // --- il contenuto che si genera da solo ---
        Content contenuto = null;
        ConfigurationSection sezioneContenuto = primaSezione(radice, "content", "contenuto", "elenco");
        if (sezioneContenuto != null) {
            contenuto = contenuto(sezioneContenuto, larghezza, dimensione, errori);
        }

        // --- la parte da finestra di dialogo ---
        MenuDialog dialogo = tipo.dialogo() ? dialogo(m, radice, errori) : null;

        if (item.isEmpty() && contenuto == null && !tipo.dialogo()) {
            errori.add("questo menu non ha nessun item: si aprira' vuoto.");
        }

        return new MenuDef(nome, tipo, righe, titolo, aggiornamento, comandi, permesso, argomenti,
                apriSe, apertura, chiusura, item, contenuto, dialogo, chiusuraLibera, errori);
    }

    static MenuDef vuoto(String nome, List<String> errori) {
        return new MenuDef(nome, MenuType.CHEST, 3, "&cMenu con errori", 0, List.of(), null,
                List.of(), Requirements.NESSUNO, List.of(), List.of(), List.of(), null, null, true, errori);
    }

    // ----------------------------------------------------------------- dialogo

    private static MenuDialog dialogo(ConfigurationSection m, ConfigurationSection radice,
                                   List<String> errori) {
        // Le chiavi del dialogo si accettano sia dentro "menu:" sia in cima al file: sono la
        // sostanza di questo menu, e discutere di dove metterle e' tempo perso per chi lo scrive.
        List<String> corpo = elencoDiTesti(m, "body", "corpo", "testo");
        if (corpo.isEmpty()) {
            corpo = elencoDiTesti(radice, "body", "corpo", "testo");
        }

        List<MenuDialog.Campo> campi = new ArrayList<>();
        ConfigurationSection sezioneCampi = primaSezione(m, "inputs", "campi");
        if (sezioneCampi == null) {
            sezioneCampi = primaSezione(radice, "inputs", "campi");
        }
        if (sezioneCampi != null) {
            for (String chiave : sezioneCampi.getKeys(false)) {
                ConfigurationSection s = sezioneCampi.getConfigurationSection(chiave);
                if (s == null) {
                    errori.add("il campo \"" + chiave + "\" non e' un blocco di impostazioni.");
                    continue;
                }
                MenuDialog.TipoCampo tipoCampo = MenuDialog.TipoCampo.leggi(testoCon(s, "text", "type", "tipo"));
                if (tipoCampo == null) {
                    errori.add("campo \"" + chiave + "\": tipo sconosciuto \"" + testo(s, "type", "tipo")
                            + "\" (validi: testo, booleano, numero, scelta).");
                    continue;
                }
                List<String> opzioni = elencoDiTesti(s, "opzioni", "options", "valori");
                if (tipoCampo == MenuDialog.TipoCampo.SCELTA && opzioni.isEmpty()) {
                    errori.add("campo \"" + chiave + "\": una scelta senza opzioni non si puo' fare.");
                    continue;
                }
                campi.add(new MenuDialog.Campo(chiave, tipoCampo,
                        testoCon(s, chiave, "label", "etichetta"),
                        testoCon(s, "", "default", "iniziale"),
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
        Object grezzoBottoni = primo(m, "buttons", "bottoni");
        if (grezzoBottoni == null) {
            grezzoBottoni = primo(radice, "buttons", "bottoni");
        }
        if (grezzoBottoni instanceof List<?> lista) {
            for (Object o : lista) {
                Map<String, Object> b = mappa(o);
                if (b == null) {
                    errori.add("un bottone non e' scritto come un blocco: lo salto.");
                    continue;
                }
                YamlConfiguration finto = new YamlConfiguration();
                for (Map.Entry<String, Object> e : b.entrySet()) {
                    metti(finto, e.getKey(), e.getValue());
                }
                String etichetta = testo(b, "label", "etichetta", "nome", "testo");
                if (etichetta == null) {
                    errori.add("un bottone non ha l'etichetta: lo salto.");
                    continue;
                }
                bottoni.add(new MenuDialog.Bottone(etichetta,
                        testo(b, "tooltip", "suggerimento", "descrizione"),
                        primo(b, "width", "larghezza") instanceof Number n ? n.intValue() : 150,
                        requisiti(finto, errori, "show_requirements", "mostra_se"),
                        azioni(finto, errori, "actions", "azioni")));
            }
        }

        if (bottoni.isEmpty()) {
            // Senza bottoni resta solo la crocetta per chiudere: e' un avviso, non un errore,
            // e per un messaggio di sola lettura va benissimo.
            errori.add("dialogo senza bottoni: si potra' solo leggere e chiudere.");
        }
        boolean pausa = booleanoCon(m, booleanoCon(radice, false, "pause", "pausa"), "pause", "pausa");
        return new MenuDialog(corpo, campi, bottoni, pausa);
    }

    // ------------------------------------------------------------------- item

    /**
     * @param conCaselle falso per l'item modello di un contenuto: quello non ha caselle proprie,
     *                   le prende dall'elenco che lo ospita.
     */
    private static ItemDef item(String chiave, ConfigurationSection s, int larghezza, int dimensione,
                                List<String> errori, boolean conCaselle) {
        ItemDef def = new ItemDef();
        def.nome(chiave);

        if (conCaselle) {
            Object slot = primo(s, "slot", "slots", "casella", "caselle");
            if (slot == null) {
                errori.add("l'item \"" + chiave + "\" non dice in quale casella va (chiave slot).");
                return null;
            }
            List<String> problemiSlot = new ArrayList<>();
            List<Integer> caselle = Slot.leggi(slot, larghezza, dimensione, problemiSlot);
            for (String p : problemiSlot) {
                errori.add("item \"" + chiave + "\": " + p);
            }
            if (caselle.isEmpty()) {
                errori.add("l'item \"" + chiave + "\" non finisce in nessuna casella valida: lo salto.");
                return null;
            }
            def.caselle(caselle);
        }

        String materiale = testo(s, "id", "material", "materiale", "item", "tipo");
        if (materiale == null && testo(s, "head", "testa", "skull") == null) {
            errori.add("l'item \"" + chiave + "\" non dice che item e' (chiave id).");
            return null;
        }
        def.materiale(materiale == null ? "PLAYER_HEAD" : materiale);

        Object quantita = primo(s, "amount", "quantity", "quantita");
        if (quantita != null) {
            def.quantita(String.valueOf(quantita));
        }

        String nomeVisibile = testo(s, "display_name", "name", "nome", "titolo");
        if (nomeVisibile != null) {
            def.titolo(nomeVisibile);
        }
        def.descrizione(elencoDiTesti(s, "lore", "descrizione", "testo"));
        def.incantesimi(elencoDiTesti(s, "enchantments", "enchants", "incantesimi"));
        def.luccica(booleanoCon(s, false, "glow", "luccica"));
        def.indistruttibile(booleanoCon(s, false, "unbreakable", "indistruttibile"));
        def.nascondiDettagli(booleanoCon(s, false, "hide_details", "hide_attributes", "hide_all",
                "nascondi_dettagli"));

        Object modello = primo(s, "custom_model_data", "modello_custom", "modello");
        if (modello != null) {
            def.modelloCustom(String.valueOf(modello));
        }
        String modelloItem = testo(s, "item_model", "modello_item");
        if (modelloItem != null) {
            def.modelloItem(modelloItem);
        }
        String colore = testo(s, "color", "colore");
        if (colore != null) {
            def.colore(colore);
        }
        String testa = testo(s, "head", "testa", "skull", "owner");
        if (testa != null) {
            def.testa(testa);
        }
        String grezzo = testo(s, "components", "nbt", "custom_nbt", "avanzate", "componenti");
        if (grezzo != null) {
            def.grezzo(grezzo);
        }

        String prezzo = testo(s, "price", "prezzo", "costo");
        if (prezzo != null) {
            def.prezzo(prezzo);
        }
        String dai = testo(s, "give", "dai", "merce", "articolo");
        if (dai != null) {
            def.dai(dai);
        }
        String vendi = testo(s, "sell", "vendi", "prezzo_vendita");
        if (vendi != null) {
            def.vendi(vendi);
        }
        if (vendi != null && dai == null) {
            errori.add("l'item \"" + chiave + "\" si puo' vendere ma non dice cosa: aggiungi la chiave give.");
        }

        def.mostraSe(requisiti(s, errori, "show_requirements", "mostra_se", "mostra_requisiti"));
        for (Click c : Click.values()) {
            Requirements r = requisiti(s, errori, c.chiaveRequisiti(), c.chiaveRequisitiItaliana());
            if (!r.vuoto()) {
                def.clicSe(c, r);
            }
            List<Action> a = azioni(s, errori, c.chiaveAzioni(), c.chiaveAzioniItaliana());
            if (!a.isEmpty()) {
                def.azioni(c, a);
            }
        }
        def.attesaFraClic(interoCon(s, 0, "cooldown", "attesa_fra_clic"));

        def.calcolaSeDinamico();
        return def;
    }

    private static Content contenuto(ConfigurationSection s, int larghezza, int dimensione,
                                       List<String> errori) {
        Content.Fonte fonte = Content.Fonte.leggi(testo(s, "source", "fonte"));
        if (fonte == null) {
            errori.add("contenuto: fonte sconosciuta \"" + testo(s, "source", "fonte")
                    + "\" (valide: giocatori_online, lista, placeholder). Salto il contenuto.");
            return null;
        }

        List<String> problemiSlot = new ArrayList<>();
        List<Integer> caselle = Slot.leggi(primo(s, "slot", "slots", "caselle"),
                larghezza, dimensione, problemiSlot);
        for (String p : problemiSlot) {
            errori.add("contenuto: " + p);
        }
        if (caselle.isEmpty()) {
            errori.add("contenuto: nessuna casella valida in cui mettere le voci. Salto il contenuto.");
            return null;
        }

        ConfigurationSection sezioneVoce = primaSezione(s, "entry", "voce", "modello", "item");
        if (sezioneVoce == null) {
            errori.add("contenuto: manca il blocco \"entry\" che dice com'e' fatta una voce.");
            return null;
        }
        ItemDef voce = item("voce", sezioneVoce, larghezza, dimensione, errori, false);
        if (voce == null) {
            return null;
        }

        return new Content(fonte, testoCon(s, "", "placeholder", "parametro"),
                elencoDiTesti(s, "list", "lista"), testoCon(s, ",", "separator", "separatore"),
                caselle, voce);
    }

    // ------------------------------------------------------------- requisiti

    private static Requirements requisiti(ConfigurationSection padre, List<String> errori, String... chiavi) {
        Object grezzo = primo(padre, chiavi);
        if (grezzo == null) {
            return Requirements.NESSUNO;
        }

        List<Requirement> elenco = new ArrayList<>();
        int minimo = 0;
        List<Action> negate = List.of();

        if (grezzo instanceof List<?> lista) {
            for (Object o : lista) {
                Requirement r = requisito(mappa(o), errori);
                if (r != null) {
                    elenco.add(r);
                }
            }
            return new Requirements(elenco, 0, negate);
        }

        ConfigurationSection s = primaSezione(padre, chiavi);
        if (s == null) {
            errori.add("il blocco " + chiavi[0] + " non e' scritto come mi aspetto: lo ignoro.");
            return Requirements.NESSUNO;
        }
        minimo = interoCon(s, 0, "minimum", "minimum_requirements", "minimo");
        negate = azioni(s, errori, "deny_actions", "deny_commands", "azioni_negate");

        ConfigurationSection dentro = primaSezione(s, "requirements", "requisiti");
        ConfigurationSection dove = dentro != null ? dentro : s;
        for (String k : dove.getKeys(false)) {
            if (dove == s && CHIAVI_DI_SERVIZIO.contains(k)) {
                continue;
            }
            ConfigurationSection uno = dove.getConfigurationSection(k);
            if (uno == null) {
                errori.add("il requisito \"" + k + "\" non e' un blocco di impostazioni.");
                continue;
            }
            Requirement r = requisito(uno.getValues(false), errori);
            if (r != null) {
                elenco.add(r);
            }
        }
        return new Requirements(elenco, minimo, negate);
    }

    private static Requirement requisito(Map<String, Object> valori, List<String> errori) {
        if (valori == null) {
            return null;
        }
        Object tipoScritto = valori.get("type") != null ? valori.get("type") : valori.get("tipo");
        Requirement.Tipo tipo = Requirement.Tipo.leggi(tipoScritto == null ? null : String.valueOf(tipoScritto));
        if (tipo == null) {
            errori.add("requisito di tipo sconosciuto: \"" + tipoScritto + "\" (validi: "
                    + String.join(", ", Requirement.tipiDisponibili()) + ").");
            return null;
        }
        String chiave = testo(valori, "key", "chiave", "input", "placeholder", "permission", "permesso");
        String valore = testo(valori, "value", "valore", "output", "nome");
        Object quantita = primo(valori, "amount", "quantity", "quantita", "importo");
        Object uguale = primo(valori, "match", "uguale", "risultato");

        int q = 1;
        if (quantita != null) {
            Double n = com.teolo.magixmenus.util.Text.numero(String.valueOf(quantita));
            if (n == null) {
                errori.add("requisito " + tipo + ": la quantita' \"" + quantita + "\" non e' un numero.");
            } else {
                q = (int) (double) n;
            }
        }
        if (chiave == null && (tipo == Requirement.Tipo.PERMESSO || tipo == Requirement.Tipo.EQUAZIONE)) {
            errori.add("requisito " + tipo + ": manca la chiave.");
            return null;
        }
        return new Requirement(tipo, chiave == null ? "" : chiave, valore == null ? "" : valore, q,
                uguale == null || Boolean.parseBoolean(String.valueOf(uguale)));
    }

    // ---------------------------------------------------------------- azioni

    private static List<Action> azioni(ConfigurationSection padre, List<String> errori, String... chiavi) {
        Object grezzo = primo(padre, chiavi);
        if (grezzo == null) {
            return List.of();
        }
        List<Action> out = new ArrayList<>();
        if (grezzo instanceof List<?> lista) {
            for (Object o : lista) {
                Action a = azione(o, errori);
                if (a != null) {
                    out.add(a);
                }
            }
        } else {
            Action a = azione(grezzo, errori);
            if (a != null) {
                out.add(a);
            }
        }
        return out;
    }

    private static Action azione(Object o, List<String> errori) {
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
            Requirements requisiti;
            if (condizione instanceof String testo) {
                // La forma corta: "if: %saldo% >= 100" e' un'equazione, il caso che capita sempre.
                requisiti = new Requirements(List.of(new Requirement(Requirement.Tipo.EQUAZIONE,
                        testo, "", 1, true)), 0, List.of());
            } else {
                requisiti = requisiti(sezioneFinta("if", condizione), errori, "if");
            }
            List<Action> allora = sottoAzioni(mappa.get("then") != null ? mappa.get("then") : mappa.get("allora"), errori);
            List<Action> altrimenti = sottoAzioni(mappa.get("else") != null ? mappa.get("else") : mappa.get("altrimenti"), errori);
            return new Action(Action.Tipo.SE, "", requisiti, allora, altrimenti);
        }

        String riga = String.valueOf(o).trim();
        if (riga.isEmpty()) {
            return null;
        }
        int duePunti = riga.indexOf(':');
        String nomeTipo = duePunti < 0 ? riga : riga.substring(0, duePunti);
        String argomento = duePunti < 0 ? "" : riga.substring(duePunti + 1).trim();

        Action.Tipo tipo = Action.Tipo.leggi(nomeTipo);
        if (tipo == null) {
            errori.add("azione sconosciuta: \"" + riga + "\" (tipi validi: "
                    + String.join(", ", Action.tipiDisponibili()) + ").");
            return null;
        }
        if (tipo.vuoleArgomento() && argomento.isEmpty()) {
            errori.add("l'azione \"" + nomeTipo + "\" vuole qualcosa dopo i due punti.");
            return null;
        }
        return Action.di(tipo, argomento);
    }

    private static List<Action> sottoAzioni(Object o, List<String> errori) {
        if (o == null) {
            return List.of();
        }
        List<Action> out = new ArrayList<>();
        if (o instanceof List<?> lista) {
            for (Object x : lista) {
                Action a = azione(x, errori);
                if (a != null) {
                    out.add(a);
                }
            }
        } else {
            Action a = azione(o, errori);
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
    private static ConfigurationSection sezioneFinta(String chiave, Object valore) {
        YamlConfiguration finta = new YamlConfiguration();
        metti(finta, chiave, valore);
        return finta;
    }

    private static void metti(ConfigurationSection dove, String chiave, Object valore) {
        Map<String, Object> m = mappa(valore);
        if (m != null) {
            dove.createSection(chiave, m);
        } else {
            dove.set(chiave, valore);
        }
    }

    /**
     * Le chiavi che dentro un blocco di requisiti NON sono un requisito.
     *
     * Sta qui e non sparsa in un "if" perche' ogni nome nuovo va aggiunto in un posto solo: la
     * volta che ci si dimentica, quella chiave viene letta come se fosse una condizione e il
     * menu si comporta in un modo che non si spiega guardando il file.
     */
    private static final java.util.Set<String> CHIAVI_DI_SERVIZIO = java.util.Set.of(
            "minimum", "minimum_requirements", "minimo",
            "deny_actions", "deny_commands", "azioni_negate");

    /** Come {@link #testo} ma con un valore di ripiego se nessuna delle chiavi c'e'. */
    private static String testoCon(ConfigurationSection s, String ripiego, String... chiavi) {
        String v = testo(s, chiavi);
        return v == null ? ripiego : v;
    }

    private static int interoCon(ConfigurationSection s, int ripiego, String... chiavi) {
        Object o = primo(s, chiavi);
        if (o instanceof Number n) {
            return n.intValue();
        }
        Double d = o == null ? null : com.teolo.magixmenus.util.Text.numero(String.valueOf(o));
        return d == null ? ripiego : (int) (double) d;
    }

    private static boolean booleanoCon(ConfigurationSection s, boolean ripiego, String... chiavi) {
        Object o = primo(s, chiavi);
        if (o instanceof Boolean b) {
            return b;
        }
        return o == null ? ripiego : Boolean.parseBoolean(String.valueOf(o));
    }

    /** Il valore della prima chiave che esiste, fra quelle passate (le altre sono i sinonimi). */
    private static Object primo(ConfigurationSection s, String... chiavi) {
        for (String k : chiavi) {
            if (k != null && s.isSet(k)) {
                return s.get(k);
            }
        }
        return null;
    }

    private static Object primo(Map<String, Object> m, String... chiavi) {
        for (String k : chiavi) {
            if (k != null && m.containsKey(k)) {
                return m.get(k);
            }
        }
        return null;
    }

    private static ConfigurationSection primaSezione(ConfigurationSection s, String... chiavi) {
        for (String k : chiavi) {
            if (k != null && s.isConfigurationSection(k)) {
                return s.getConfigurationSection(k);
            }
        }
        return null;
    }

    private static String testo(ConfigurationSection s, String... chiavi) {
        Object o = primo(s, chiavi);
        return o == null ? null : String.valueOf(o);
    }

    private static String testo(Map<String, Object> m, String... chiavi) {
        Object o = primo(m, chiavi);
        return o == null ? null : String.valueOf(o);
    }

    /**
     * Le liste di testi si scrivono sia come elenco sia come riga sola: {@code lore: "una riga"}
     * e {@code lore: ["una", "due"]} sono tutte e due valide, perche' pretendere le parentesi
     * quadre per una riga sola e' l'errore che si fa piu' spesso.
     */
    private static List<String> elencoDiTesti(ConfigurationSection s, String... chiavi) {
        Object o = primo(s, chiavi);
        if (o == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> lista) {
            for (Object x : lista) {
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
