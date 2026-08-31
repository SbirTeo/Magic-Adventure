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

    public static void scrivi(JavaPlugin plugin, MenuManager gestore) {
        JsonObject radice = new JsonObject();
        radice.addProperty("generato", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        radice.addProperty("versione", plugin.getPluginMeta().getVersion());

        JsonObject menu = new JsonObject();
        for (MenuDef d : gestore.tutti()) {
            menu.add(d.nome(), menu(d));
        }
        radice.add("menu", menu);
        radice.add("catalogo", catalogo());

        File f = new File(plugin.getDataFolder(), "menus.json");
        try {
            Files.writeString(f.toPath(),
                    new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(radice),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            plugin.getLogger().warning("Non sono riuscito a scrivere menus.json per l'editor del sito: "
                    + e.getMessage());
        }
    }

    // ------------------------------------------------------------------- menu

    private static JsonObject menu(MenuDef d) {
        JsonObject o = new JsonObject();
        o.addProperty("nome", d.nome());
        o.addProperty("tipo", d.tipo().nomeFile());
        o.addProperty("righe", d.righe());
        o.addProperty("caselle", d.dimensione());
        o.addProperty("larghezza", d.tipo().larghezza());
        o.addProperty("titolo", d.titolo());
        o.addProperty("aggiornamento", d.aggiornamentoTick());
        o.add("comandi", testi(d.comandi()));
        o.addProperty("permission", d.permesso());
        o.add("argomenti", testi(d.argomenti()));
        o.addProperty("chiusura_libera", d.chiusuraLibera());
        o.add("apri_se", requisiti(d.apriSe()));
        o.add("azioni_apertura", azioni(d.azioniApertura()));
        o.add("azioni_chiusura", azioni(d.azioniChiusura()));
        o.addProperty("dinamico", d.dinamico());

        JsonArray item = new JsonArray();
        for (ItemDef i : d.item()) {
            item.add(item(i, true));
        }
        o.add("item", item);

        if (d.contenuto() != null) {
            JsonObject c = new JsonObject();
            c.addProperty("fonte", d.contenuto().fonte().nomeFile());
            c.addProperty("placeholder", d.contenuto().parametro());
            c.add("lista", testi(d.contenuto().lista()));
            c.addProperty("separatore", d.contenuto().separatore());
            c.add("slot", numeri(d.contenuto().caselle()));
            c.add("voce", item(d.contenuto().voce(), false));
            o.add("contenuto", c);
        }
        if (d.dialogo() != null) {
            o.add("dialogo", dialogo(d.dialogo()));
        }
        o.add("errori", testi(d.errori()));
        return o;
    }

    private static JsonObject item(ItemDef i, boolean conCaselle) {
        JsonObject o = new JsonObject();
        o.addProperty("nome", i.nome());
        if (conCaselle) {
            o.add("slot", numeri(i.caselle()));
        }
        o.addProperty("id", i.materiale());
        o.addProperty("quantita", i.quantita());
        o.addProperty("titolo", i.titolo());
        o.add("descrizione", testi(i.descrizione()));
        o.add("incantesimi", testi(i.incantesimi()));
        o.addProperty("luccica", i.luccica());
        o.addProperty("indistruttibile", i.indistruttibile());
        o.addProperty("nascondi_dettagli", i.nascondiDettagli());
        o.addProperty("modello_custom", i.modelloCustom());
        o.addProperty("modello_item", i.modelloItem());
        o.addProperty("colore", i.colore());
        o.addProperty("testa", i.testa());
        o.addProperty("avanzate", i.grezzo());
        o.addProperty("prezzo", i.prezzo());
        o.addProperty("dai", i.dai());
        o.addProperty("vendi", i.vendi());
        o.addProperty("attesa_fra_clic", i.attesaFraClic());
        o.addProperty("dinamico", i.dinamico());
        o.add("mostra_se", requisiti(i.mostraSe()));

        // Le chiavi sono quelle del FILE ("actions", "right_click_actions"): cosi' chi
        // riscrive lo YAML dal sito non deve tradurre niente, e un nome nuovo aggiunto a Click
        // arriva all'editor da solo.
        JsonObject clic = new JsonObject();
        JsonObject requisitiClic = new JsonObject();
        for (Click c : Click.values()) {
            clic.add(c.chiaveAzioni(), azioni(i.azioniGrezze(c)));
            requisitiClic.add(c.chiaveRequisiti(), requisiti(i.clicSe(c)));
        }
        o.add("azioni", clic);
        o.add("click_se", requisitiClic);
        return o;
    }

    private static JsonObject dialogo(MenuDialog d) {
        JsonObject o = new JsonObject();
        o.add("corpo", testi(d.corpo()));
        o.addProperty("pausa", d.mettiInPausa());

        JsonArray campi = new JsonArray();
        for (MenuDialog.Campo c : d.campi()) {
            JsonObject x = new JsonObject();
            x.addProperty("chiave", c.chiave());
            x.addProperty("tipo", c.tipo().nomeFile());
            x.addProperty("etichetta", c.etichetta());
            x.addProperty("iniziale", c.iniziale());
            x.addProperty("da", c.minimo());
            x.addProperty("a", c.massimo());
            x.addProperty("passo", c.passo());
            x.addProperty("lunghezza", c.lunghezza());
            x.addProperty("larghezza", c.larghezza());
            x.addProperty("piu_righe", c.piuRighe());
            x.add("opzioni", testi(c.opzioni()));
            campi.add(x);
        }
        o.add("campi", campi);

        JsonArray bottoni = new JsonArray();
        for (MenuDialog.Bottone b : d.bottoni()) {
            JsonObject x = new JsonObject();
            x.addProperty("etichetta", b.etichetta());
            x.addProperty("suggerimento", b.suggerimento());
            x.addProperty("larghezza", b.larghezza());
            x.add("mostra_se", requisiti(b.mostraSe()));
            x.add("azioni", azioni(b.azioni()));
            bottoni.add(x);
        }
        o.add("bottoni", bottoni);
        return o;
    }

    // ------------------------------------------------------- requisiti e azioni

    private static JsonObject requisiti(Requirements r) {
        JsonObject o = new JsonObject();
        o.addProperty("minimo", r.minimo());
        JsonArray elenco = new JsonArray();
        for (Requirement x : r.elenco()) {
            JsonObject u = new JsonObject();
            u.addProperty("tipo", x.tipo().nomeFile());
            u.addProperty("chiave", x.chiave());
            u.addProperty("valore", x.valore());
            u.addProperty("quantita", x.quantita());
            u.addProperty("uguale", x.uguale());
            u.addProperty("descrizione", x.descrizione());
            elenco.add(u);
        }
        o.add("requisiti", elenco);
        o.add("azioni_negate", azioni(r.azioniNegate()));
        return o;
    }

    private static JsonArray azioni(List<Action> elenco) {
        JsonArray a = new JsonArray();
        for (Action x : elenco) {
            JsonObject o = new JsonObject();
            o.addProperty("tipo", x.tipo().nomeFile());
            if (x.tipo() == Action.Tipo.SE) {
                o.add("condizione", requisiti(x.condizione()));
                o.add("allora", azioni(x.allora()));
                o.add("altrimenti", azioni(x.altrimenti()));
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
        o.add("tipi_menu", testi(MenuType.nomi()));
        o.add("tipi_requisito", testi(Requirement.tipiDisponibili()));
        o.add("tipi_azione", testi(Action.tipiDisponibili()));

        // Anche qui i nomi del FILE, non quelli delle costanti Java: l'editor li usa come
        // chiavi, e devono combaciare con quelle dei gruppi di azioni.
        JsonArray clic = new JsonArray();
        for (Click c : Click.values()) {
            clic.add(c.chiaveAzioni());
        }
        o.add("tasti", clic);

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

    private static JsonArray testi(List<String> elenco) {
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
