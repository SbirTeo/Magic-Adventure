package com.teolo.magixmenus.menu;

import org.bukkit.event.inventory.InventoryType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Che finestra si apre.
 *
 * Il baule e' l'unico che decide quante caselle avere (da 1 a 6 righe da 9); tutti gli altri hanno
 * la forma che gli ha dato Minecraft e non si discute — un'incudine ha tre caselle, una tramoggia
 * cinque, e non c'e' configurazione che possa cambiarlo.
 *
 * <h2>La larghezza</h2>
 * Serve solo a chi scrive {@code slot: riga:2} o {@code slot: bordo}: per sapere dove finisce una
 * riga bisogna sapere quanto e' larga la finestra. Minecraft non la dichiara da nessuna parte
 * nell'API, quindi sta scritta qui.
 *
 * <h2>Quelli con un meccanismo dentro</h2>
 * Incudine, fornace, tavolo da lavoro e simili non sono contenitori qualsiasi: hanno delle caselle
 * che il gioco tratta in modo speciale (l'incudine vuole due item e produce il terzo, la fornace
 * consuma il combustibile). Come menu funzionano, ma sono fatti per un uso solo — leggere quello
 * che il giocatore SCRIVE nell'incudine — e per il resto conviene sempre il baule.
 */
public enum TipoMenu {

    /** Il baule: da 1 a 6 righe da 9 caselle. E' quello che serve nel 95% dei casi. */
    CHEST(null, 0, 9, true),
    /** Come il baule ma sempre 27 caselle. */
    BARILE("BARREL", 27, 9, false),
    /** L'incudine: si usa per farsi scrivere qualcosa, il testo digitato diventa %input%. */
    INCUDINE("ANVIL", 3, 3, false),
    TRAMOGGIA("HOPPER", 5, 5, false),
    DISTRIBUTORE("DISPENSER", 9, 3, false),
    DROPPER("DROPPER", 9, 3, false),
    FORNACE("FURNACE", 3, 3, false),
    ALAMBICCO("BREWING", 5, 5, false),
    INCANTESIMI("ENCHANTING", 2, 2, false),
    TAVOLO_DA_LAVORO("WORKBENCH", 10, 5, false),
    MOLA("GRINDSTONE", 3, 3, false),
    TELAIO("LOOM", 4, 4, false),
    CARTOGRAFO("CARTOGRAPHY", 3, 3, false),
    FUCINA("SMITHING", 4, 4, false),
    TAGLIAPIETRE("STONECUTTER", 2, 2, false),
    /** La finestra di dialogo di Minecraft: testo, bottoni e campi da riempire. Non e' un inventario. */
    DIALOGO(null, 0, 0, false);

    /**
     * Il nome del tipo di finestra di Minecraft, non il tipo stesso.
     *
     * Tenere qui la costante {@code InventoryType} obbligherebbe a caricare i registri del gioco
     * nel momento in cui questa enum viene toccata la prima volta — cioe' mentre si LEGGONO i file
     * dei menu. Il nome e' solo testo: si risolve quando serve davvero, cioe' quando una finestra
     * si apre per davvero.
     */
    private final String inventario;
    private final int dimensione;
    private final int larghezza;
    private final boolean righeSuMisura;

    TipoMenu(String inventario, int dimensione, int larghezza, boolean righeSuMisura) {
        this.inventario = inventario;
        this.dimensione = dimensione;
        this.larghezza = larghezza;
        this.righeSuMisura = righeSuMisura;
    }

    /**
     * Il nome con cui questo tipo si scrive nei file.
     *
     * Le costanti Java restano in italiano (il codice del progetto parla italiano); i file, no:
     * le chiavi e i valori dei config di tutti i plugin Magix si scrivono in inglese.
     */
    public String nomeFile() {
        return switch (this) {
            case CHEST -> "chest";
            case BARILE -> "barrel";
            case INCUDINE -> "anvil";
            case TRAMOGGIA -> "hopper";
            case DISTRIBUTORE -> "dispenser";
            case DROPPER -> "dropper";
            case FORNACE -> "furnace";
            case ALAMBICCO -> "brewing";
            case INCANTESIMI -> "enchanting";
            case TAVOLO_DA_LAVORO -> "workbench";
            case MOLA -> "grindstone";
            case TELAIO -> "loom";
            case CARTOGRAFO -> "cartography";
            case FUCINA -> "smithing";
            case TAGLIAPIETRE -> "stonecutter";
            case DIALOGO -> "dialog";
        };
    }

    public InventoryType inventario() {
        return inventario == null ? null : InventoryType.valueOf(inventario);
    }

    public int larghezza() {
        return larghezza;
    }

    /** Le righe si scelgono nel file? Vero solo per il baule. */
    public boolean righeSuMisura() {
        return righeSuMisura;
    }

    public boolean dialogo() {
        return this == DIALOGO;
    }

    /** Quante caselle ha, viste le righe scelte (che contano solo per il baule). */
    public int dimensione(int righe) {
        return righeSuMisura ? Math.max(1, Math.min(6, righe)) * 9 : dimensione;
    }

    /**
     * Il tipo scritto nel file. Accetta l'italiano e il nome inglese di Minecraft, cosi' un menu
     * copiato da un altro plugin si apre senza doverlo tradurre.
     */
    public static TipoMenu leggi(String s) {
        if (s == null || s.isBlank()) {
            return CHEST;
        }
        String n = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (n) {
            case "CHEST", "BAULE", "CASSA", "GENERIC_9X1", "GENERIC_9X2", "GENERIC_9X3",
                 "GENERIC_9X4", "GENERIC_9X5", "GENERIC_9X6" -> CHEST;
            case "BARREL", "BARILE" -> BARILE;
            case "ANVIL", "INCUDINE" -> INCUDINE;
            case "HOPPER", "TRAMOGGIA" -> TRAMOGGIA;
            case "DISPENSER", "DISTRIBUTORE" -> DISTRIBUTORE;
            case "DROPPER" -> DROPPER;
            case "FURNACE", "FORNACE" -> FORNACE;
            case "BREWING", "BREWING_STAND", "ALAMBICCO" -> ALAMBICCO;
            case "ENCHANTING", "ENCHANTING_TABLE", "INCANTESIMI" -> INCANTESIMI;
            case "WORKBENCH", "CRAFTING", "TAVOLO_DA_LAVORO" -> TAVOLO_DA_LAVORO;
            case "GRINDSTONE", "MOLA" -> MOLA;
            case "LOOM", "TELAIO" -> TELAIO;
            case "CARTOGRAPHY", "CARTOGRAPHY_TABLE", "CARTOGRAFO" -> CARTOGRAFO;
            case "SMITHING", "SMITHING_TABLE", "FUCINA" -> FUCINA;
            case "STONECUTTER", "TAGLIAPIETRE" -> TAGLIAPIETRE;
            case "DIALOG", "DIALOGO", "FINESTRA" -> DIALOGO;
            default -> null;
        };
    }

    /** I nomi scrivibili nel file, per i messaggi d'errore e per l'editor sul sito. */
    public static List<String> nomi() {
        List<String> out = new ArrayList<>();
        for (TipoMenu t : values()) {
            out.add(t.nomeFile());
        }
        return out;
    }
}
