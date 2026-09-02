package com.teolo.magixmenus.menu;

import com.teolo.magixmenus.actions.Action;
import com.teolo.magixmenus.requirements.Requirements;
import com.teolo.magixmenus.util.Text;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Un item del menu: com'e' fatto, dove va, chi lo vede e cosa succede se lo si clicca.
 *
 * <h2>Le caselle</h2>
 * Un item puo' stare in piu' caselle contemporaneamente ({@code slot: 2,3,4} oppure {@code 2-10}):
 * e' la stessa definizione disegnata piu' volte, non copie da tenere allineate a mano. Il vetro di
 * sfondo di un menu e' una riga sola di configurazione invece di quarantacinque.
 *
 * <h2>La priorita'</h2>
 * Due item possono chiedere la stessa casella. Vince il PRIMO scritto nel file cha abbia i
 * {@code mostra_se} soddisfatti. E' cosi' che si fa il bottone che cambia faccia — verde
 * "disponibile", grigio "ti manca ancora qualcosa" — senza duplicare il menu: due definizioni sulla
 * stessa casella, la piu' esigente scritta per prima.
 *
 * <h2>Fermo o vivo</h2>
 * {@link #dinamico()} dice se qualcosa in questo item cambia da solo nel tempo (un placeholder nel
 * nome, nella lore, nella quantita', o dei requisiti che dipendono dal giocatore). Gli item che non
 * cambiano vengono costruiti una volta e non si toccano piu' a ogni aggiornamento: e' quello che
 * permette a un menu di aggiornarsi ogni tick senza pesare.
 */
public final class ItemDef {

    private String name = "";
    private List<Integer> slots = List.of();

    // --- aspetto (tutti i testi possono contenere placeholder) ---
    private String materiale = "STONE";
    private String quantita = "1";
    private String title;                       // il nome visibile; null = quello dell'item
    private List<String> description = List.of();
    private List<String> incantesimi = List.of();
    private boolean luccica;
    private boolean indistruttibile;
    private boolean hideDetails;
    private String modelloCustom;                // custom_model_data
    private String modelloItem;                  // item_model (1.21.4+)
    private String color;                       // pelle, pozione, fuoco d'artificio: "#RRGGBB"
    private String testa;                        // nome giocatore, URL o texture base64
    private String raw;                       // NBT/componenti per quello che qui non e' previsto

    // --- negozio: vedi negozio/Shop.java per il perche' non sono requisiti e azioni ---
    private String prezzo;                       // quanto costa comprarlo
    private String dai;                          // cosa passa di mano ("DIAMOND 4", "se_stesso")
    private String vendi;                        // a quanto lo ricompra il server (clic destro)

    // --- comportamento ---
    private Requirements showIf = Requirements.NESSUNO;
    private final Map<Click, Requirements> clickIf = new EnumMap<>(Click.class);
    private final Map<Click, List<Action>> actions = new EnumMap<>(Click.class);
    private int clickDelay;                   // secondi di pausa fra un clic e il successivo
    private boolean dinamico;

    ItemDef() {
    }

    // ------------------------------------------------------------------ lettura

    public String name() {
        return name;
    }

    public List<Integer> slots() {
        return slots;
    }

    public String materiale() {
        return materiale;
    }

    public String quantita() {
        return quantita;
    }

    public String title() {
        return title;
    }

    public List<String> description() {
        return description;
    }

    public List<String> incantesimi() {
        return incantesimi;
    }

    public boolean luccica() {
        return luccica;
    }

    public boolean indistruttibile() {
        return indistruttibile;
    }

    public boolean hideDetails() {
        return hideDetails;
    }

    public String modelloCustom() {
        return modelloCustom;
    }

    public String modelloItem() {
        return modelloItem;
    }

    public String color() {
        return color;
    }

    public String testa() {
        return testa;
    }

    public String raw() {
        return raw;
    }

    public String prezzo() {
        return prezzo;
    }

    public String dai() {
        return dai;
    }

    public String vendi() {
        return vendi;
    }

    /** E' un articolo di negozio? Allora il clic passa prima da {@code negozio.Shop}. */
    public boolean articolo() {
        return (prezzo != null && !prezzo.isBlank()) || (vendi != null && !vendi.isBlank());
    }

    public Requirements showIf() {
        return showIf;
    }

    public Requirements clickIf(Click c) {
        return clickIf.getOrDefault(c, Requirements.NESSUNO);
    }

    public boolean hasClickRequirements() {
        return !clickIf.isEmpty();
    }

    public int clickDelay() {
        return clickDelay;
    }

    public boolean dinamico() {
        return dinamico;
    }

    /**
     * Le azioni da eseguire per questo clic: prima quelle del tasto premuto, poi quelle generiche.
     * Vedi il perche' in {@link Click}.
     */
    public List<Action> actionsFor(Click c) {
        List<Action> proprie = actions.get(c);
        List<Action> generiche = actions.get(Click.QUALSIASI);
        if (proprie == null || proprie.isEmpty()) {
            return generiche == null ? List.of() : generiche;
        }
        if (generiche == null || generiche.isEmpty() || c == Click.QUALSIASI) {
            return proprie;
        }
        List<Action> tutte = new ArrayList<>(proprie);
        tutte.addAll(generiche);
        return tutte;
    }

    public boolean cliccabile() {
        return !actions.isEmpty();
    }

    /**
     * Le azioni scritte per QUESTO tasto e basta, senza aggiungerci quelle comuni.
     *
     * Serve a chi deve rimettere in fila il file com'era scritto — l'esportazione per l'editor
     * del sito: unire i due gruppi come fa {@link #azioniPer} li farebbe comparire due volte al
     * prossimo salvataggio.
     */
    public List<Action> rawActions(Click c) {
        List<Action> a = actions.get(c);
        return a == null ? List.of() : a;
    }

    // ------------------------------------------------------- scrittura (caricatore)

    void name(String v) {
        this.name = v;
    }

    void slots(List<Integer> v) {
        this.slots = List.copyOf(v);
    }

    void materiale(String v) {
        this.materiale = v;
    }

    void quantita(String v) {
        this.quantita = v;
    }

    void title(String v) {
        this.title = v;
    }

    void description(List<String> v) {
        this.description = List.copyOf(v);
    }

    void incantesimi(List<String> v) {
        this.incantesimi = List.copyOf(v);
    }

    void luccica(boolean v) {
        this.luccica = v;
    }

    void indistruttibile(boolean v) {
        this.indistruttibile = v;
    }

    void hideDetails(boolean v) {
        this.hideDetails = v;
    }

    void modelloCustom(String v) {
        this.modelloCustom = v;
    }

    void modelloItem(String v) {
        this.modelloItem = v;
    }

    void color(String v) {
        this.color = v;
    }

    void testa(String v) {
        this.testa = v;
    }

    void raw(String v) {
        this.raw = v;
    }

    void prezzo(String v) {
        this.prezzo = v;
    }

    void dai(String v) {
        this.dai = v;
    }

    void vendi(String v) {
        this.vendi = v;
    }

    void showIf(Requirements v) {
        this.showIf = v;
    }

    void clickIf(Click c, Requirements v) {
        this.clickIf.put(c, v);
    }

    void actions(Click c, List<Action> v) {
        this.actions.put(c, List.copyOf(v));
    }

    void clickDelay(int v) {
        this.clickDelay = v;
    }

    /**
     * Decide una volta per tutte se questo item ha qualcosa che cambia nel tempo.
     *
     * Si chiama a fine caricamento, non a ogni disegno: e' una risposta che dipende solo da com'e'
     * scritto il file, e ricalcolarla sessanta volte al secondo per ogni item di ogni menu aperto
     * sarebbe esattamente il genere di spreco che questo campo serve a evitare.
     */
    void computeIfDynamic() {
        dinamico = Text.dinamico(materiale) || Text.dinamico(quantita) || Text.dinamico(title)
                || Text.dinamico(description) || Text.dinamico(testa) || Text.dinamico(color)
                || Text.dinamico(modelloCustom) || Text.dinamico(modelloItem)
                || Text.dinamico(raw) || Text.dinamico(incantesimi)
                // Un item con dei mostra_se e' vivo per forza: quelle condizioni possono
                // diventare vere mentre il menu e' aperto, e allora l'item deve comparire.
                || !showIf.vuoto()
                || Text.dinamico(prezzo) || Text.dinamico(vendi);
    }
}
