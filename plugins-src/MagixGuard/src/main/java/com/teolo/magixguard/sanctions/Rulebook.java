package com.teolo.magixguard.sanctions;

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
public final class Rulebook {

    private Rulebook() {
    }

    /** Costruisce il blocco HTML. Niente stile inline: le classi le veste il CSS del sito. */
    public static String generate(SanctionsConfig cfg) {
        StringBuilder b = new StringBuilder();

        String intro = cfg.rulesIntro;
        if (intro != null && !intro.isBlank()) {
            b.append("<p>").append(escapeHtml(intro.replace("{dimezzamento}",
                    String.valueOf(Math.round(cfg.halfLifeDays))))).append("</p>\n");
        }

        // --- cosa costa cosa ---
        b.append("<table><thead><tr><th>Violazione</th><th>Punti</th><th>Dove vale</th></tr></thead><tbody>\n");
        for (SanctionsConfig.Category c : cfg.categories.values()) {
            if (c.points() <= 0) {
                continue;   // le voci senza punti non sono violazioni: sono etichette di servizio
            }
            b.append("<tr><td><strong>").append(escapeHtml(c.name())).append("</strong>");
            if (c.description() != null && !c.description().isBlank()) {
                b.append("<br><span class=\"regolamento-dettaglio\">").append(escapeHtml(c.description())).append("</span>");
            }
            b.append("</td><td>").append(c.points()).append("</td><td>")
             .append(escapeHtml(c.scope().label())).append("</td></tr>\n");
        }
        b.append("</tbody></table>\n");

        // --- cosa succede a quanti punti ---
        b.append("<p>Al raggiungimento di questi punti scatta il provvedimento:</p>\n");
        b.append("<table><thead><tr><th>Punti</th><th>Provvedimento</th><th>Durata</th></tr></thead><tbody>\n");
        // Le soglie sono tenute dalla piu' alta perche' e' cosi' che si applicano;
        // per leggerle, pero', si va dalla piu' bassa: e' il percorso che fa una persona.
        for (int i = cfg.thresholds.size() - 1; i >= 0; i--) {
            SanctionsConfig.Threshold s = cfg.thresholds.get(i);
            b.append("<tr><td>").append(s.points()).append("</td><td>")
             .append(escapeHtml(s.type().label())).append("</td><td>")
             .append(escapeHtml(Duration.write(s.duration()))).append("</td></tr>\n");
        }
        b.append("</tbody></table>\n");

        b.append("<p class=\"regolamento-dettaglio\">Ogni provvedimento si puo' contestare: "
                + "nella pagina del provvedimento c'e' il modulo per il ricorso, e resta "
                + "raggiungibile anche a chi e' bloccato. Il ban permanente non e' mai "
                + "automatico: lo decide sempre una persona.</p>\n");

        return b.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
