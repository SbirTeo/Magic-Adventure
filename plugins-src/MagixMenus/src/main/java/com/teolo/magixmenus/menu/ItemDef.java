package com.teolo.magixmenus.menu;

import com.teolo.magixmenus.azioni.Azione;
import com.teolo.magixmenus.requisiti.Requisiti;
import com.teolo.magixmenus.util.Testo;

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

    private String nome = "";
    private List<Integer> caselle = List.of();

    // --- aspetto (tutti i testi possono contenere placeholder) ---
    private String materiale = "STONE";
    private String quantita = "1";
    private String titolo;                       // il nome visibile; null = quello dell'item
    private List<String> descrizione = List.of();
    private List<String> incantesimi = List.of();
    private boolean luccica;
    private boolean indistruttibile;
    private boolean nascondiDettagli;
    private String modelloCustom;                // custom_model_data
    private String modelloItem;                  // item_model (1.21.4+)
    private String colore;                       // pelle, pozione, fuoco d'artificio: "#RRGGBB"
    private String testa;                        // nome giocatore, URL o texture base64
    private String grezzo;                       // NBT/componenti per quello che qui non e' previsto

    // --- negozio: vedi negozio/Negozio.java per il perche' non sono requisiti e azioni ---
    private String prezzo;                       // quanto costa comprarlo
    private String dai;                          // cosa passa di mano ("DIAMOND 4", "se_stesso")
    private String vendi;                        // a quanto lo ricompra il server (clic destro)

    // --- comportamento ---
    private Requisiti mostraSe = Requisiti.NESSUNO;
    private final Map<Clic, Requisiti> clicSe = new EnumMap<>(Clic.class);
    private final Map<Clic, List<Azione>> azioni = new EnumMap<>(Clic.class);
    private int attesaFraClic;                   // secondi di pausa fra un clic e il successivo
    private boolean dinamico;

    ItemDef() {
    }

    // ------------------------------------------------------------------ lettura

    public String nome() {
        return nome;
    }

    public List<Integer> caselle() {
        return caselle;
    }

    public String materiale() {
        return materiale;
    }

    public String quantita() {
        return quantita;
    }

    public String titolo() {
        return titolo;
    }

    public List<String> descrizione() {
        return descrizione;
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

    public boolean nascondiDettagli() {
        return nascondiDettagli;
    }

    public String modelloCustom() {
        return modelloCustom;
    }

    public String modelloItem() {
        return modelloItem;
    }

    public String colore() {
        return colore;
    }

    public String testa() {
        return testa;
    }

    public String grezzo() {
        return grezzo;
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

    /** E' un articolo di negozio? Allora il clic passa prima da {@code negozio.Negozio}. */
    public boolean articolo() {
        return (prezzo != null && !prezzo.isBlank()) || (vendi != null && !vendi.isBlank());
    }

    public Requisiti mostraSe() {
        return mostraSe;
    }

    public Requisiti clicSe(Clic c) {
        return clicSe.getOrDefault(c, Requisiti.NESSUNO);
    }

    public boolean haRequisitiDiClic() {
        return !clicSe.isEmpty();
    }

    public int attesaFraClic() {
        return attesaFraClic;
    }

    public boolean dinamico() {
        return dinamico;
    }

    /**
     * Le azioni da eseguire per questo clic: prima quelle del tasto premuto, poi quelle generiche.
     * Vedi il perche' in {@link Clic}.
     */
    public List<Azione> azioniPer(Clic c) {
        List<Azione> proprie = azioni.get(c);
        List<Azione> generiche = azioni.get(Clic.QUALSIASI);
        if (proprie == null || proprie.isEmpty()) {
            return generiche == null ? List.of() : generiche;
        }
        if (generiche == null || generiche.isEmpty() || c == Clic.QUALSIASI) {
            return proprie;
        }
        List<Azione> tutte = new ArrayList<>(proprie);
        tutte.addAll(generiche);
        return tutte;
    }

    public boolean cliccabile() {
        return !azioni.isEmpty();
    }

    /**
     * Le azioni scritte per QUESTO tasto e basta, senza aggiungerci quelle comuni.
     *
     * Serve a chi deve rimettere in fila il file com'era scritto — l'esportazione per l'editor
     * del sito: unire i due gruppi come fa {@link #azioniPer} li farebbe comparire due volte al
     * prossimo salvataggio.
     */
    public List<Azione> azioniGrezze(Clic c) {
        List<Azione> a = azioni.get(c);
        return a == null ? List.of() : a;
    }

    // ------------------------------------------------------- scrittura (caricatore)

    void nome(String v) {
        this.nome = v;
    }

    void caselle(List<Integer> v) {
        this.caselle = List.copyOf(v);
    }

    void materiale(String v) {
        this.materiale = v;
    }

    void quantita(String v) {
        this.quantita = v;
    }

    void titolo(String v) {
        this.titolo = v;
    }

    void descrizione(List<String> v) {
        this.descrizione = List.copyOf(v);
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

    void nascondiDettagli(boolean v) {
        this.nascondiDettagli = v;
    }

    void modelloCustom(String v) {
        this.modelloCustom = v;
    }

    void modelloItem(String v) {
        this.modelloItem = v;
    }

    void colore(String v) {
        this.colore = v;
    }

    void testa(String v) {
        this.testa = v;
    }

    void grezzo(String v) {
        this.grezzo = v;
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

    void mostraSe(Requisiti v) {
        this.mostraSe = v;
    }

    void clicSe(Clic c, Requisiti v) {
        this.clicSe.put(c, v);
    }

    void azioni(Clic c, List<Azione> v) {
        this.azioni.put(c, List.copyOf(v));
    }

    void attesaFraClic(int v) {
        this.attesaFraClic = v;
    }

    /**
     * Decide una volta per tutte se questo item ha qualcosa che cambia nel tempo.
     *
     * Si chiama a fine caricamento, non a ogni disegno: e' una risposta che dipende solo da com'e'
     * scritto il file, e ricalcolarla sessanta volte al secondo per ogni item di ogni menu aperto
     * sarebbe esattamente il genere di spreco che questo campo serve a evitare.
     */
    void calcolaSeDinamico() {
        dinamico = Testo.dinamico(materiale) || Testo.dinamico(quantita) || Testo.dinamico(titolo)
                || Testo.dinamico(descrizione) || Testo.dinamico(testa) || Testo.dinamico(colore)
                || Testo.dinamico(modelloCustom) || Testo.dinamico(modelloItem)
                || Testo.dinamico(grezzo) || Testo.dinamico(incantesimi)
                // Un item con dei mostra_se e' vivo per forza: quelle condizioni possono
                // diventare vere mentre il menu e' aperto, e allora l'item deve comparire.
                || !mostraSe.vuoto()
                || Testo.dinamico(prezzo) || Testo.dinamico(vendi);
    }
}
