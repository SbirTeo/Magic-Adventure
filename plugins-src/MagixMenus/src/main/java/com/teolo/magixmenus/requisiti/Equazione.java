package com.teolo.magixmenus.requisiti;

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
public final class Equazione {

    /** Condizione che non sta in piedi: chi chiama decide se e' un no o un errore da scrivere nel log. */
    public static final class Errore extends RuntimeException {
        public Errore(String messaggio) {
            super(messaggio);
        }
    }

    private Equazione() {
    }

    /** Vero o falso per la condizione scritta. Lancia {@link Errore} se non e' leggibile. */
    public static boolean vera(String condizione) {
        if (condizione == null || condizione.isBlank()) {
            return false;
        }
        Analisi a = new Analisi(tokenizza(condizione));
        Object v = a.oppure();
        if (!a.finito()) {
            throw new Errore("non capisco cosa ci fa '" + a.corrente() + "' qui");
        }
        return verita(v);
    }

    // ------------------------------------------------------------------ i pezzi

    private enum Genere { NUMERO, TESTO, PAROLA, OPERATORE }

    private record Pezzo(Genere genere, String testo) {
    }

    private static final String[] OPERATORI_DOPPI = {"&&", "||", ">=", "<=", "==", "!="};

    /** Simboli che chiudono una parola: tutto il resto ne fa parte (accenti e underscore compresi). */
    private static final String SIMBOLI = "+-*/%^()<>!&|'\"";

    private static List<Pezzo> tokenizza(String s) {
        List<Pezzo> pezzi = new ArrayList<>();
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
                pezzi.add(new Pezzo(Genere.TESTO, s.substring(i + 1, fine)));
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
                    pezzi.add(new Pezzo(Genere.PAROLA, s.substring(i, chiusura + 1)));
                    i = chiusura + 1;
                    continue;
                }
            }
            boolean doppio = false;
            for (String op : OPERATORI_DOPPI) {
                if (s.startsWith(op, i)) {
                    pezzi.add(new Pezzo(Genere.OPERATORE, op));
                    i += 2;
                    doppio = true;
                    break;
                }
            }
            if (doppio) {
                continue;
            }
            if (SIMBOLI.indexOf(c) >= 0) {
                pezzi.add(new Pezzo(Genere.OPERATORE, String.valueOf(c)));
                i++;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                int fine = i;
                while (fine < s.length() && (Character.isDigit(s.charAt(fine)) || s.charAt(fine) == '.')) {
                    fine++;
                }
                pezzi.add(new Pezzo(Genere.NUMERO, s.substring(i, fine)));
                i = fine;
                continue;
            }
            int fine = i;
            while (fine < s.length() && !Character.isWhitespace(s.charAt(fine))
                    && SIMBOLI.indexOf(s.charAt(fine)) < 0) {
                fine++;
            }
            pezzi.add(new Pezzo(Genere.PAROLA, s.substring(i, fine)));
            i = fine;
        }
        return pezzi;
    }

    // ---------------------------------------------------------------- la lettura

    private static final class Analisi {
        private final List<Pezzo> pezzi;
        private int i;

        Analisi(List<Pezzo> pezzi) {
            this.pezzi = pezzi;
        }

        boolean finito() {
            return i >= pezzi.size();
        }

        String corrente() {
            return finito() ? "" : pezzi.get(i).testo();
        }

        private boolean operatore(String... quali) {
            if (finito() || pezzi.get(i).genere() != Genere.OPERATORE) {
                return false;
            }
            for (String q : quali) {
                if (pezzi.get(i).testo().equals(q)) {
                    return true;
                }
            }
            return false;
        }

        Object oppure() {
            Object sinistra = entrambi();
            while (operatore("||")) {
                i++;
                Object destra = entrambi();
                sinistra = verita(sinistra) || verita(destra);
            }
            return sinistra;
        }

        Object entrambi() {
            Object sinistra = confronto();
            while (operatore("&&")) {
                i++;
                Object destra = confronto();
                sinistra = verita(sinistra) && verita(destra);
            }
            return sinistra;
        }

        Object confronto() {
            Object sinistra = somma();
            if (operatore(">=", "<=", "==", "!=", "<", ">")) {
                String op = pezzi.get(i).testo();
                i++;
                Object destra = somma();
                return confronta(sinistra, op, destra);
            }
            return sinistra;
        }

        Object somma() {
            Object sinistra = prodotto();
            while (operatore("+", "-")) {
                String op = pezzi.get(i).testo();
                i++;
                Object destra = prodotto();
                // Il "+" fra due cose che numeri non sono unisce il testo: e' l'unico modo
                // di comporre una frase dentro una condizione, e non costa niente.
                if (op.equals("+") && (comeNumero(sinistra) == null || comeNumero(destra) == null)) {
                    sinistra = testo(sinistra) + testo(destra);
                } else {
                    sinistra = op.equals("+") ? numero(sinistra) + numero(destra)
                            : numero(sinistra) - numero(destra);
                }
            }
            return sinistra;
        }

        Object prodotto() {
            Object sinistra = potenza();
            while (operatore("*", "/", "%")) {
                String op = pezzi.get(i).testo();
                i++;
                double destra = numero(potenza());
                double a = numero(sinistra);
                if ((op.equals("/") || op.equals("%")) && destra == 0) {
                    throw new Errore("divisione per zero");
                }
                sinistra = switch (op) {
                    case "*" -> a * destra;
                    case "/" -> a / destra;
                    default -> a % destra;
                };
            }
            return sinistra;
        }

        Object potenza() {
            Object base = unario();
            if (operatore("^")) {
                i++;
                return Math.pow(numero(base), numero(unario()));
            }
            return base;
        }

        Object unario() {
            if (operatore("-")) {
                i++;
                return -numero(unario());
            }
            if (operatore("!")) {
                i++;
                return !verita(unario());
            }
            if (operatore("+")) {
                i++;
                return unario();
            }
            return primario();
        }

        Object primario() {
            if (finito()) {
                throw new Errore("la condizione finisce a meta'");
            }
            Pezzo p = pezzi.get(i);
            if (p.genere() == Genere.OPERATORE && p.testo().equals("(")) {
                i++;
                Object dentro = oppure();
                if (!operatore(")")) {
                    throw new Errore("manca una parentesi chiusa");
                }
                i++;
                return dentro;
            }
            if (p.genere() == Genere.NUMERO) {
                i++;
                return Double.valueOf(p.testo());
            }
            if (p.genere() == Genere.TESTO) {
                i++;
                return p.testo();
            }
            if (p.genere() == Genere.PAROLA) {
                // Parole di seguito senza operatore in mezzo: una frase sola (vedi il commento
                // in cima alla classe, il caso dei nomi con gli spazi).
                StringBuilder sb = new StringBuilder(p.testo());
                i++;
                while (!finito() && pezzi.get(i).genere() == Genere.PAROLA) {
                    sb.append(' ').append(pezzi.get(i).testo());
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
            throw new Errore("non capisco '" + p.testo() + "'");
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
        int c = testo(a).compareToIgnoreCase(testo(b));
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

    private static double numero(Object o) {
        Double d = comeNumero(o);
        if (d == null) {
            if (o instanceof Boolean b) {
                return b ? 1 : 0;
            }
            throw new Errore("'" + o + "' non e' un numero");
        }
        return d;
    }

    private static String testo(Object o) {
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
