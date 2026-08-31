package com.teolo.magixguard.sanzioni;

/**
 * La tabella delle sanzioni che compare nel regolamento del sito, generata da
 * {@code sanzioni.yml}.
 *
 * <p><b>Perche' esiste.</b> Un regolamento scritto a mano e una configurazione che decide
 * davvero divergono al primo ritocco, e a quel punto il server punisce in un modo e promette
 * un altro — che e' esattamente cio' che un giocatore ti mette davanti quando fa ricorso.
 * Qui la pagina pubblica non descrive le sanzioni: le <b>rispecchia</b>.</p>
 *
 * <p>Nel text della pagina si mette il segnaposto {@code [[SANZIONI]]}: il sito lo sostituisce
 * con questo blocco a ogni visita, e questo blocco si riscrive a ogni avvio e a ogni reload.</p>
 */
public final class Regolamento {

    private Regolamento() {
    }

    /** Costruisce il blocco HTML. Niente stile inline: le classi le veste il CSS del sito. */
    public static String genera(SanzioniConfig cfg) {
        StringBuilder b = new StringBuilder();

        String intro = cfg.introduzioneRegolamento;
        if (intro != null && !intro.isBlank()) {
            b.append("<p>").append(esc(intro.replace("{dimezzamento}",
                    String.valueOf(Math.round(cfg.dimezzamentoGiorni))))).append("</p>\n");
        }

        // --- cosa costa cosa ---
        b.append("<table><thead><tr><th>Violazione</th><th>Punti</th><th>Dove vale</th></tr></thead><tbody>\n");
        for (SanzioniConfig.Categoria c : cfg.categorie.values()) {
            if (c.punti() <= 0) {
                continue;   // le voci senza punti non sono violazioni: sono etichette di servizio
            }
            b.append("<tr><td><strong>").append(esc(c.nome())).append("</strong>");
            if (c.descrizione() != null && !c.descrizione().isBlank()) {
                b.append("<br><span class=\"regolamento-dettaglio\">").append(esc(c.descrizione())).append("</span>");
            }
            b.append("</td><td>").append(c.punti()).append("</td><td>")
             .append(esc(c.ambito().etichetta())).append("</td></tr>\n");
        }
        b.append("</tbody></table>\n");

        // --- cosa succede a quanti punti ---
        b.append("<p>Al raggiungimento di questi punti scatta il provvedimento:</p>\n");
        b.append("<table><thead><tr><th>Punti</th><th>Provvedimento</th><th>Durata</th></tr></thead><tbody>\n");
        // Le soglie sono tenute dalla piu' alta perche' e' cosi' che si applicano;
        // per leggerle, pero', si va dalla piu' bassa: e' il percorso che fa una persona.
        for (int i = cfg.soglie.size() - 1; i >= 0; i--) {
            SanzioniConfig.Soglia s = cfg.soglie.get(i);
            b.append("<tr><td>").append(s.punti()).append("</td><td>")
             .append(esc(s.tipo().etichetta())).append("</td><td>")
             .append(esc(Durata.scrivi(s.durata()))).append("</td></tr>\n");
        }
        b.append("</tbody></table>\n");

        b.append("<p class=\"regolamento-dettaglio\">Ogni provvedimento si puo' contestare: "
                + "nella pagina del provvedimento c'e' il modulo per il ricorso, e resta "
                + "raggiungibile anche a chi e' bloccato. Il ban permanente non e' mai "
                + "automatico: lo decide sempre una persona.</p>\n");

        return b.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
