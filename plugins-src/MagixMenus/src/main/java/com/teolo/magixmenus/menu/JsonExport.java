package com.teolo.magixmenus.menu;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.teolo.magixmenus.actions.Action;
import com.teolo.magixmenus.requirements.Requirements;
import com.teolo.magixmenus.requirements.Requirement;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Scrive {@code plugins/MagixMenus/menus.json}: tutto quello che il plugin ha capito dei file dei
 * menu, in una forma che l'editor del sito puo' leggere.
 *
 * <h2>Perche' non lasciare che il sito legga gli .yml</h2>
 * PHP, su questo VPS, non ha un lettore di YAML. Scriverne uno significherebbe avere DUE
 * interpretazioni degli stessi file — quella del plugin e quella del sito — e prima o poi le due
 * divergono: l'editor mostrerebbe un menu diverso da quello che i giocatori vedono, che e' il modo
 * peggiore di sbagliare, perche' nessuno se ne accorge finche' non e' online.
 *
 * Cosi' invece la lettura la fa una volta sola chi la deve fare comunque. Il sito riceve il
 * risultato — errori compresi — e sull'editor compare esattamente cio' che il server ha capito.
 * Il sito, dall'altra parte, sa solo SCRIVERE YAML, che e' la meta' facile del problema.
 *
 * <h2>Il catalogo</h2>
 * Nel file finisce anche l'elenco dei tipi di menu, di requisito e di azione che questa versione
 * del plugin conosce, e tutti i nomi di item del gioco. Le tendine dell'editor si riempiono da li':
 * non possono offrire qualcosa che il plugin non sa fare, e non vanno aggiornate a mano quando il
 * plugin impara qualcosa di nuovo.
 *
 * <h2>I nomi dei campi qui dentro</h2>
 * Sono quelli del modello Java, in italiano ({@code titolo}, {@code descrizione}, {@code prezzo}),
 * NON quelli del file .yml, che sono in inglese. Non e' una svista: questo file non lo scrive
 * nessuno a mano — e' il trasporto fra il plugin e il sito — e tenerlo uguale alle classi Java
 * significa che chi legge {@code JsonExport} accanto a {@code ItemDef} vede le stesse parole.
 * La traduzione verso i nomi del file avviene in un punto solo: {@code website/includes/menu_yaml.php}.
 *
 * Fanno eccezione le chiavi dei GRUPPI DI AZIONI e di REQUISITI PER TASTO
 * ({@code actions}, {@code right_click_actions}, {@code click_requirements}...), che sono gia'
 * quelle del file: li' il sito non deve tradurre niente, e un tasto nuovo arriva all'editor da solo.
 *
 * <h2>Cosa NON sopravvive</h2>
 * I commenti. Un file riscritto dall'editor perde le note che ci aveva messo chi lo aveva scritto a
 * mano — sta scritto in cima a ogni file salvato dal sito, cosi' nessuno ci casca.
 */
public final class JsonExport {

    private JsonExport() {
    }

    public static void write(JavaPlugin plugin, MenuManager manager) {
        JsonObject root = new JsonObject();
        root.addProperty("generato", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        root.addProperty("versione", plugin.getPluginMeta().getVersion());

        JsonObject menu = new JsonObject();
        for (MenuDef d : manager.tutti()) {
            menu.add(d.name(), menu(d));
        }
        root.add("menu", menu);
        root.add("catalogo", catalogo());

        File f = new File(plugin.getDataFolder(), "menus.json");
        try {
            Files.writeString(f.toPath(),
                    new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("Non sono riuscito a scrivere menus.json per l'editor del sito: "
                    + e.getMessage());
        }
    }

    // ------------------------------------------------------------------- menu

    private static JsonObject menu(MenuDef d) {
        JsonObject o = new JsonObject();
        o.addProperty("nome", d.name());
        o.addProperty("tipo", d.type().fileName());
        o.addProperty("righe", d.rows());
        o.addProperty("caselle", d.dimensione());
        o.addProperty("larghezza", d.type().larghezza());
        o.addProperty("titolo", d.title());
        o.addProperty("aggiornamento", d.aggiornamentoTick());
        o.add("comandi", texts(d.commands()));
        o.addProperty("permission", d.permesso());
        o.add("argomenti", texts(d.arguments()));
        o.addProperty("chiusura_libera", d.freeClose());
        o.add("apri_se", requirements(d.openIf()));
        o.add("azioni_apertura", actions(d.openActions()));
        o.add("azioni_chiusura", actions(d.closeActions()));
        o.addProperty("dinamico", d.dinamico());

        JsonArray item = new JsonArray();
        for (ItemDef i : d.item()) {
            item.add(item(i, true));
        }
        o.add("item", item);

        if (d.content() != null) {
            JsonObject c = new JsonObject();
            c.addProperty("fonte", d.content().fonte().fileName());
            c.addProperty("placeholder", d.content().parametro());
            c.add("lista", texts(d.content().list()));
            c.addProperty("separatore", d.content().separatore());
            c.add("slot", numeri(d.content().slots()));
            c.add("voce", item(d.content().entry(), false));
            o.add("contenuto", c);
        }
        if (d.dialog() != null) {
            o.add("dialogo", dialog(d.dialog()));
        }
        o.add("errori", texts(d.errori()));
        return o;
    }

    private static JsonObject item(ItemDef i, boolean withSlots) {
        JsonObject o = new JsonObject();
        o.addProperty("nome", i.name());
        if (withSlots) {
            o.add("slot", numeri(i.slots()));
        }
        o.addProperty("id", i.materiale());
        o.addProperty("quantita", i.quantita());
        o.addProperty("titolo", i.title());
        o.add("descrizione", texts(i.description()));
        o.add("incantesimi", texts(i.incantesimi()));
        o.addProperty("luccica", i.luccica());
        o.addProperty("indistruttibile", i.indistruttibile());
        o.addProperty("nascondi_dettagli", i.hideDetails());
        o.addProperty("modello_custom", i.modelloCustom());
        o.addProperty("modello_item", i.modelloItem());
        o.addProperty("colore", i.color());
        o.addProperty("testa", i.testa());
        o.addProperty("avanzate", i.raw());
        o.addProperty("prezzo", i.prezzo());
        o.addProperty("dai", i.dai());
        o.addProperty("vendi", i.vendi());
        o.addProperty("attesa_fra_clic", i.clickDelay());
        o.addProperty("dinamico", i.dinamico());
        o.add("mostra_se", requirements(i.showIf()));

        // Le chiavi sono quelle del FILE ("actions", "right_click_actions"): cosi' chi
        // riscrive lo YAML dal sito non deve tradurre niente, e un nome nuovo aggiunto a Click
        // arriva all'editor da solo.
        JsonObject click = new JsonObject();
        JsonObject clickRequirements = new JsonObject();
        for (Click c : Click.values()) {
            click.add(c.actionsKey(), actions(i.rawActions(c)));
            clickRequirements.add(c.requirementsKey(), requirements(i.clickIf(c)));
        }
        o.add("azioni", click);
        o.add("click_se", clickRequirements);
        return o;
    }

    private static JsonObject dialog(MenuDialog d) {
        JsonObject o = new JsonObject();
        o.add("corpo", texts(d.body()));
        o.addProperty("pausa", d.pauseUpdates());

        JsonArray fields = new JsonArray();
        for (MenuDialog.Field c : d.fields()) {
            JsonObject x = new JsonObject();
            x.addProperty("chiave", c.key());
            x.addProperty("tipo", c.type().fileName());
            x.addProperty("etichetta", c.label());
            x.addProperty("iniziale", c.iniziale());
            x.addProperty("da", c.minimum());
            x.addProperty("a", c.maximum());
            x.addProperty("passo", c.step());
            x.addProperty("lunghezza", c.lunghezza());
            x.addProperty("larghezza", c.larghezza());
            x.addProperty("piu_righe", c.multiLine());
            x.add("opzioni", texts(c.opzioni()));
            fields.add(x);
        }
        o.add("campi", fields);

        JsonArray bottoni = new JsonArray();
        for (MenuDialog.Bottone b : d.bottoni()) {
            JsonObject x = new JsonObject();
            x.addProperty("etichetta", b.label());
            x.addProperty("suggerimento", b.suggestion());
            x.addProperty("larghezza", b.larghezza());
            x.add("mostra_se", requirements(b.showIf()));
            x.add("azioni", actions(b.actions()));
            bottoni.add(x);
        }
        o.add("bottoni", bottoni);
        return o;
    }

    // ------------------------------------------------------- requisiti e azioni

    private static JsonObject requirements(Requirements r) {
        JsonObject o = new JsonObject();
        o.addProperty("minimo", r.minimum());
        JsonArray elenco = new JsonArray();
        for (Requirement x : r.elenco()) {
            JsonObject u = new JsonObject();
            u.addProperty("tipo", x.type().fileName());
            u.addProperty("chiave", x.key());
            u.addProperty("valore", x.value());
            u.addProperty("quantita", x.quantita());
            u.addProperty("uguale", x.equal());
            u.addProperty("descrizione", x.description());
            elenco.add(u);
        }
        o.add("requisiti", elenco);
        o.add("azioni_negate", actions(r.deniedActions()));
        return o;
    }

    private static JsonArray actions(List<Action> elenco) {
        JsonArray a = new JsonArray();
        for (Action x : elenco) {
            JsonObject o = new JsonObject();
            o.addProperty("tipo", x.type().fileName());
            if (x.type() == Action.Type.SE) {
                o.add("condizione", requirements(x.condizione()));
                o.add("allora", actions(x.allora()));
                o.add("altrimenti", actions(x.altrimenti()));
            } else {
                o.addProperty("argomento", x.argomento());
            }
            a.add(o);
        }
        return a;
    }

    // --------------------------------------------------------------- catalogo

    private static JsonObject catalogo() {
        JsonObject o = new JsonObject();
        o.add("tipi_menu", texts(MenuType.names()));
        o.add("tipi_requisito", texts(Requirement.availableTypes()));
        o.add("tipi_azione", texts(Action.availableTypes()));

        // Anche qui i nomi del FILE, non quelli delle costanti Java: l'editor li usa come
        // chiavi, e devono combaciare con quelle dei gruppi di azioni.
        JsonArray click = new JsonArray();
        for (Click c : Click.values()) {
            click.add(c.actionsKey());
        }
        o.add("tasti", click);

        // Tutti gli item del gioco che si possono davvero mettere in una casella: i blocchi che
        // esistono solo come blocco piazzato (le porte a meta', il fuoco) non hanno un'icona da
        // mostrare e in un menu non ci vanno.
        JsonArray materiali = new JsonArray();
        for (Material m : Material.values()) {
            if (m.isItem() && !m.isAir() && !m.isLegacy()) {
                materiali.add(m.name());
            }
        }
        o.add("item", materiali);

        JsonArray incantesimi = new JsonArray();
        for (org.bukkit.enchantments.Enchantment e : org.bukkit.Registry.ENCHANTMENT) {
            incantesimi.add(e.getKey().getKey().toUpperCase(Locale.ROOT));
        }
        o.add("incantesimi", incantesimi);

        JsonArray suoni = new JsonArray();
        for (org.bukkit.Sound s : org.bukkit.Sound.values()) {
            suoni.add(s.name());
        }
        o.add("suoni", suoni);
        return o;
    }

    private static JsonArray texts(List<String> elenco) {
        JsonArray a = new JsonArray();
        for (String s : elenco) {
            a.add(s);
        }
        return a;
    }

    private static JsonArray numeri(List<Integer> elenco) {
        JsonArray a = new JsonArray();
        for (Integer n : elenco) {
            a.add(n);
        }
        return a;
    }
}
