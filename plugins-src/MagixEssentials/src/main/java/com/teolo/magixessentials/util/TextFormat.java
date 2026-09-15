package com.teolo.magixessentials.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Una riga scritta nel config diventa testo colorato a schermo.
 *
 * <p>Due modi di scrivere i colori, uno <b>o</b> l'altro nella stessa riga: i <b>codici classici</b>
 * ({@code &a}, {@code &l}, {@code &#RRGGBB}) oppure i <b>tag</b> di MiniMessage ({@code <bold>},
 * {@code <gradient:#C046E8:#A8DC2C>}). Si riconoscono dai triangoli: se in una riga c'e' un tag,
 * quella riga viene letta come tag e le {@code &} restano scritte com'erano. E' la stessa regola
 * della MOTD ({@code motd.MotdText}), ripetuta qui perche' quella classe non deve conoscere niente
 * che non esista anche su Velocity, e questa invece serve al mondo di gioco.</p>
 */
public final class TextFormat {

    /** &-codes + &#RRGGBB (il formato che produce %magixweb_namecolor%) + il vecchio &x&r&r... di Bungee. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private static final MiniMessage TAGS = MiniMessage.miniMessage();

    /** Un {@code <} seguito da una lettera (o da una barra) e chiuso da un {@code >}: quello e' un tag. */
    private static final Pattern HAS_TAGS = Pattern.compile("<[a-zA-Z/][^<>]*>");

    private TextFormat() {
    }

    /**
     * La riga diventa testo colorato. Un tag scritto male non fa sparire la riga: si ripiega sui
     * codici {@code &}, che al massimo lasciano i triangoli scritti a schermo — meglio brutta che
     * assente.
     */
    public static Component component(String line) {
        if (line == null || line.isEmpty()) {
            return Component.empty();
        }
        // Un placeholder puo' tornare i codici col § (e' quello che capisce il client, e diversi
        // plugin lo usano gia' tradotto): per noi vale come una &, altrimenti resterebbe a schermo.
        String text = line.replace('§', '&');
        if (HAS_TAGS.matcher(text).find()) {
            try {
                return TAGS.deserialize(text);
            } catch (RuntimeException e) {
                return LEGACY.deserialize(text);
            }
        }
        return LEGACY.deserialize(text);
    }

    /** Il testo senza colori ne' tag: per il log, e per sapere se di una riga resta qualcosa da leggere. */
    public static String plain(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        return PlainTextComponentSerializer.plainText().serialize(component(line));
    }

    /**
     * Il colore con cui la riga <b>finisce</b>: quello che vale per il testo che verrebbe subito
     * dopo. Serve alla targhetta del gioco, dove il nome vero e' un pezzo a parte e non eredita il
     * colore scritto prima di lui: per dargli quello giusto bisogna leggerlo qui.
     *
     * <p>Torna {@code null} se la riga non ha colori.</p>
     */
    public static TextColor endColor(String line) {
        Component c = component(line);
        return endColor(c, c.style().color());
    }

    /**
     * Lo stesso colore, ridotto al piu' vicino fra i <b>16</b> del gioco: le squadre dello scoreboard
     * — cioe' il nome vero nella targhetta — non sanno che farsene di un esadecimale.
     */
    public static NamedTextColor nearestNamed(String line) {
        TextColor color = endColor(line);
        return color == null ? null : NamedTextColor.nearestTo(color);
    }

    /**
     * L'ultimo pezzo del componente, in ordine di lettura, col colore che si porta dietro dai
     * genitori. Si segue sempre l'ultimo figlio: un codice colore in fondo alla riga diventa un
     * pezzo vuoto con quel colore, ed e' proprio quello che ci interessa.
     */
    private static TextColor endColor(Component c, TextColor inherited) {
        TextColor here = c.style().color() != null ? c.style().color() : inherited;
        List<Component> children = c.children();
        if (children.isEmpty()) {
            return here;
        }
        return endColor(children.get(children.size() - 1), here);
    }
}
