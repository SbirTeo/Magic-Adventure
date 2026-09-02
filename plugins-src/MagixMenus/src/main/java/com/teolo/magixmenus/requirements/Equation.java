package com.teolo.magixmenus.requirements;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Il piccolo calcolatore che sta dietro al requisito EQUAZIONE.
 *
 * Riceve la condizione con i placeholder GIA' risolti — {@code "%vault_eco_balance% >= 1000"}
 * arriva qui come {@code "2500 >= 1000"} — e risponde vero o falso.
 *
 * <h2>Cosa capisce</h2>
 * <pre>
 * 2500 &gt;= 1000                 confronti: &gt; &lt; &gt;= &lt;= == !=
 * 10 + 5 * 2 &lt; 30              conti: + - * / % ^ e le parentesi
 * eroi == eroi                 testo: se i due lati non sono numeri, si confrontano come parole
 * "Fazione dei Draghi" != ""   testo con spazi fra virgolette (o senza: vedi sotto)
 * vero &amp;&amp; 3 &gt; 1        e/o: &amp;&amp; || e la negazione !
 * </pre>
 *
 * <h2>Perche' scritto a mano</h2>
 * Gli altri plugin di menu valutano queste condizioni con un motore JavaScript. Dal JDK 15 quel
 * motore non fa piu' parte di Java, e imbarcarne uno significherebbe portarsi dentro un linguaggio
 * intero — con tutto quello che ci si puo' scrivere — per fare dei confronti fra numeri. Qui la
 * grammatica e' questa e finisce qui: nessuna condizione di un menu puo' leggere un file o aprire
 * una connessione, perche' non c'e' modo di esprimerlo.
 *
 * <h2>Le parole senza virgolette</h2>
 * Un placeholder che restituisce un nome con gli spazi ("Fazione dei Draghi") lascerebbe una
 * condizione tipo {@code Fazione dei Draghi == Fazione dei Draghi}, che senza virgolette sarebbe
 * illeggibile. Le parole di seguito, senza un operatore in mezzo, vengono quindi riunite in una
 * frase sola: cosi' quella condizione funziona anche se chi l'ha scritta non ha pensato alle
 * virgolette. Restano consigliate, ma non sono una trappola.
 */
public final class Equation {

    /** Condizione che non sta in piedi: chi chiama decide se e' un no o un errore da scrivere nel log. */
    public static final class Errore extends RuntimeException {
        public Errore(String message) {
            super(message);
        }
    }

    private Equation() {
    }

    /** Vero o falso per la condizione scritta. Lancia {@link Errore} se non e' leggibile. */
    public static boolean real(String condizione) {
        if (condizione == null || condizione.isBlank()) {
            return false;
        }
        Analisi a = new Analisi(tokenizza(condizione));
        Object v = a.oppure();
        if (!a.finito()) {
            throw new Errore("non capisco cosa ci fa '" + a.current() + "' qui");
        }
        return verita(v);
    }

    // ------------------------------------------------------------------ i pezzi

    private enum Genere { NUMERO, TEXT, PAROLA, OPERATOR }

    private record Pezzo(Genere genere, String text) {
    }

    private static final String[] OPERATORI_DOPPI = {"&&", "||", ">=", "<=", "==", "!="};

    /** Simboli che chiudono una parola: tutto il resto ne fa parte (accenti e underscore compresi). */
    private static final String SIMBOLI = "+-*/%^()<>!&|'\"";

    private static List<Pezzo> tokenizza(String s) {
        List<Pezzo> pieces = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                int fine = s.indexOf(c, i + 1);
                if (fine < 0) {
                    throw new Errore("virgolette aperte e mai chiuse");
                }
                pieces.add(new Pezzo(Genere.TEXT, s.substring(i + 1, fine)));
                i = fine + 1;
                continue;
            }
            if (c == '%') {
                // Un placeholder che nessuno ha saputo risolvere arriva qui per intero
                // ("%vault_eco_balance%"). Trattarlo come il segno di resto darebbe un errore di
                // sintassi e una condizione che non si capisce: cosi' invece resta una parola, e
                // "%a% == %a%" e' vero mentre "%a% >= 10" e' semplicemente falso.
                int chiusura = s.indexOf('%', i + 1);
                if (chiusura > i + 1 && s.substring(i + 1, chiusura).matches("[A-Za-z0-9_.:\\-]+")) {
                    pieces.add(new Pezzo(Genere.PAROLA, s.substring(i, chiusura + 1)));
                    i = chiusura + 1;
                    continue;
                }
            }
            boolean doppio = false;
            for (String op : OPERATORI_DOPPI) {
                if (s.startsWith(op, i)) {
                    pieces.add(new Pezzo(Genere.OPERATOR, op));
                    i += 2;
                    doppio = true;
                    break;
                }
            }
            if (doppio) {
                continue;
            }
            if (SIMBOLI.indexOf(c) >= 0) {
                pieces.add(new Pezzo(Genere.OPERATOR, String.valueOf(c)));
                i++;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                int fine = i;
                while (fine < s.length() && (Character.isDigit(s.charAt(fine)) || s.charAt(fine) == '.')) {
                    fine++;
                }
                pieces.add(new Pezzo(Genere.NUMERO, s.substring(i, fine)));
                i = fine;
                continue;
            }
            int fine = i;
            while (fine < s.length() && !Character.isWhitespace(s.charAt(fine))
                    && SIMBOLI.indexOf(s.charAt(fine)) < 0) {
                fine++;
            }
            pieces.add(new Pezzo(Genere.PAROLA, s.substring(i, fine)));
            i = fine;
        }
        return pieces;
    }

    // ---------------------------------------------------------------- la lettura

    private static final class Analisi {
        private final List<Pezzo> pieces;
        private int i;

        Analisi(List<Pezzo> pieces) {
            this.pieces = pieces;
        }

        boolean finito() {
            return i >= pieces.size();
        }

        String current() {
            return finito() ? "" : pieces.get(i).text();
        }

        private boolean operator(String... quali) {
            if (finito() || pieces.get(i).genere() != Genere.OPERATOR) {
                return false;
            }
            for (String q : quali) {
                if (pieces.get(i).text().equals(q)) {
                    return true;
                }
            }
            return false;
        }

        Object oppure() {
            Object left = entrambi();
            while (operator("||")) {
                i++;
                Object destra = entrambi();
                left = verita(left) || verita(destra);
            }
            return left;
        }

        Object entrambi() {
            Object left = confronto();
            while (operator("&&")) {
                i++;
                Object destra = confronto();
                left = verita(left) && verita(destra);
            }
            return left;
        }

        Object confronto() {
            Object left = somma();
            if (operator(">=", "<=", "==", "!=", "<", ">")) {
                String op = pieces.get(i).text();
                i++;
                Object destra = somma();
                return confronta(left, op, destra);
            }
            return left;
        }

        Object somma() {
            Object left = prodotto();
            while (operator("+", "-")) {
                String op = pieces.get(i).text();
                i++;
                Object destra = prodotto();
                // Il "+" fra due cose che numeri non sono unisce il testo: e' l'unico modo
                // di comporre una frase dentro una condizione, e non costa niente.
                if (op.equals("+") && (comeNumero(left) == null || comeNumero(destra) == null)) {
                    left = text(left) + text(destra);
                } else {
                    left = op.equals("+") ? number(left) + number(destra)
                            : number(left) - number(destra);
                }
            }
            return left;
        }

        Object prodotto() {
            Object left = potenza();
            while (operator("*", "/", "%")) {
                String op = pieces.get(i).text();
                i++;
                double destra = number(potenza());
                double a = number(left);
                if ((op.equals("/") || op.equals("%")) && destra == 0) {
                    throw new Errore("divisione per zero");
                }
                left = switch (op) {
                    case "*" -> a * destra;
                    case "/" -> a / destra;
                    default -> a % destra;
                };
            }
            return left;
        }

        Object potenza() {
            Object base = unario();
            if (operator("^")) {
                i++;
                return Math.pow(number(base), number(unario()));
            }
            return base;
        }

        Object unario() {
            if (operator("-")) {
                i++;
                return -number(unario());
            }
            if (operator("!")) {
                i++;
                return !verita(unario());
            }
            if (operator("+")) {
                i++;
                return unario();
            }
            return primario();
        }

        Object primario() {
            if (finito()) {
                throw new Errore("la condizione finisce a meta'");
            }
            Pezzo p = pieces.get(i);
            if (p.genere() == Genere.OPERATOR && p.text().equals("(")) {
                i++;
                Object inside = oppure();
                if (!operator(")")) {
                    throw new Errore("manca una parentesi chiusa");
                }
                i++;
                return inside;
            }
            if (p.genere() == Genere.NUMERO) {
                i++;
                return Double.valueOf(p.text());
            }
            if (p.genere() == Genere.TEXT) {
                i++;
                return p.text();
            }
            if (p.genere() == Genere.PAROLA) {
                // Parole di seguito senza operatore in mezzo: una frase sola (vedi il commento
                // in cima alla classe, il caso dei nomi con gli spazi).
                StringBuilder sb = new StringBuilder(p.text());
                i++;
                while (!finito() && pieces.get(i).genere() == Genere.PAROLA) {
                    sb.append(' ').append(pieces.get(i).text());
                    i++;
                }
                String parola = sb.toString();
                String basso = parola.toLowerCase(Locale.ROOT);
                if (basso.equals("true") || basso.equals("vero")) {
                    return Boolean.TRUE;
                }
                if (basso.equals("false") || basso.equals("falso")) {
                    return Boolean.FALSE;
                }
                return parola;
            }
            throw new Errore("non capisco '" + p.text() + "'");
        }
    }

    // ------------------------------------------------------------------ i valori

    private static Object confronta(Object a, String op, Object b) {
        Double na = comeNumero(a);
        Double nb = comeNumero(b);
        if (na != null && nb != null) {
            return switch (op) {
                case ">" -> na > nb;
                case "<" -> na < nb;
                case ">=" -> na >= nb;
                case "<=" -> na <= nb;
                case "==" -> na.doubleValue() == nb.doubleValue();
                default -> na.doubleValue() != nb.doubleValue();
            };
        }
        // Non sono numeri: si confrontano come parole. L'uguaglianza ignora maiuscole e
        // minuscole, perche' un nome scritto "Draghi" o "draghi" e' lo stesso nome.
        int c = text(a).compareToIgnoreCase(text(b));
        return switch (op) {
            case ">" -> c > 0;
            case "<" -> c < 0;
            case ">=" -> c >= 0;
            case "<=" -> c <= 0;
            case "==" -> c == 0;
            default -> c != 0;
        };
    }

    private static Double comeNumero(Object o) {
        if (o instanceof Double d) {
            return d;
        }
        if (o instanceof Boolean) {
            return null;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double number(Object o) {
        Double d = comeNumero(o);
        if (d == null) {
            if (o instanceof Boolean b) {
                return b ? 1 : 0;
            }
            throw new Errore("'" + o + "' non e' un numero");
        }
        return d;
    }

    private static String text(Object o) {
        if (o instanceof Double d && d == Math.rint(d) && !d.isInfinite()) {
            return String.valueOf((long) (double) d);   // 10.0 -> "10", altrimenti nessun confronto tornerebbe
        }
        return String.valueOf(o);
    }

    private static boolean verita(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Double d) {
            return d != 0;
        }
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        return !(s.isEmpty() || s.equals("false") || s.equals("falso") || s.equals("0") || s.equals("no"));
    }
}
