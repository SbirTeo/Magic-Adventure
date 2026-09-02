package com.teolo.magixmenus.item;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.teolo.magixmenus.MagixMenus;
import com.teolo.magixmenus.menu.ItemDef;
import com.teolo.magixmenus.shop.Shop;
import com.teolo.magixmenus.util.Colors;
import com.teolo.magixmenus.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Da come l'item e' scritto nel file a come si vede dentro il menu.
 *
 * <h2>Niente eccezioni verso l'alto</h2>
 * Qualunque cosa non torni — un item che non esiste, un incantesimo scritto male, un colore che
 * non e' un colore — produce comunque un item: una barriera rossa con scritto sopra cosa non va.
 * Il menu si apre, il buco si vede, e chi lo ha scritto capisce dove ha sbagliato senza andare a
 * cercare nel log. Un'eccezione lasciata passare, invece, chiuderebbe il menu a meta' disegno
 * lasciando una finestra vuota e nessuna spiegazione.
 *
 * <h2>L'ordine dei passaggi</h2>
 * Il campo grezzo (NBT/componenti) si applica per PRIMO, e tutto il resto gli va sopra. Cosi' i
 * campi comodi restano l'ultima parola: chi scrive {@code nome:} si aspetta di vedere quel nome,
 * anche se nel campo avanzate era rimasto un vecchio {@code custom_name}.
 */
public final class ItemBuilder {

    /** "SHARPNESS,5" oppure "sharpness:5" oppure "minecraft:sharpness 5". */
    private static final Pattern INCANTESIMO = Pattern.compile("^\\s*([A-Za-z0-9_:.]+)\\s*[,:; ]\\s*(\\d+)\\s*$");

    /** L'indirizzo della texture dentro il valore base64 di una testa. */
    private static final Pattern URL_TEXTURE = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");

    private ItemBuilder() {
    }

    public static ItemStack costruisci(MagixMenus plugin, Player p, Map<String, String> variabili,
                                       ItemDef def) {
        return costruisci(plugin, p, variabili, def, true);
    }

    /**
     * @param perIlMenu vero per l'item da mostrare nella casella; FALSO per l'oggetto che passa
     *        davvero di mano quando lo si compra. La differenza sono le righe automatiche del
     *        prezzo: nella casella ci vogliono, sull'oggetto in inventario no — nessuno vuole
     *        ritrovarsi una spada con scritto sopra "Costo: 1000 monete" per sempre.
     */
    public static ItemStack costruisci(MagixMenus plugin, Player p, Map<String, String> variabili,
                                       ItemDef def, boolean perIlMenu) {
        Logger log = plugin.getLogger();
        List<String> problemi = new ArrayList<>();

        String materialName = Text.raw(p, variabili, def.materiale()).trim();
        boolean testaRichiesta = def.testa() != null && !def.testa().isBlank();
        Material materiale = Material.matchMaterial(materialName);
        if (materiale == null && testaRichiesta) {
            // Chi scrive "testa: %player_name%" vuole una testa: e' inutile pretendere che si
            // ricordi anche di scrivere PLAYER_HEAD.
            materiale = Material.PLAYER_HEAD;
        }
        if (materiale == null || materiale.isAir()) {
            return barriera(def.name(), List.of("l'item \"" + materialName + "\" non esiste"));
        }
        if (testaRichiesta && materiale == Material.PLAYER_HEAD) {
            materiale = Material.PLAYER_HEAD;
        }

        ItemStack stack = new ItemStack(materiale, quantita(p, variabili, def, materiale));

        if (def.raw() != null && !def.raw().isBlank()) {
            stack = raw(log, p, variabili, def, stack, problemi);
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        if (def.title() != null) {
            meta.displayName(senzaCorsivo(Colors.component(Text.apply(p, variabili, def.title()))));
        }
        List<String> description = new ArrayList<>(def.description());
        if (perIlMenu && def.articolo()
                && plugin.getConfig().getBoolean("price-in-lore", true)) {
            // Il prezzo si scrive da solo in fondo alla descrizione: scritto a mano finirebbe in
            // due posti (la chiave prezzo e la riga di lore) e prima o poi i due direbbero cose
            // diverse. Come sono fatte quelle righe si decide in messages.yml, non qui.
            Map<String, String> values = Shop.valuesForDescription(p, variabili, def);
            if (def.prezzo() != null && !def.prezzo().isBlank()) {
                description.addAll(plugin.messages().getList("shop.price-lore",
                        "price", values.get("price")));
            }
            if (def.vendi() != null && !def.vendi().isBlank()) {
                description.addAll(plugin.messages().getList("shop.sell-lore",
                        "sell", values.get("sell")));
            }
        }
        if (!description.isEmpty()) {
            List<Component> rows = new ArrayList<>();
            for (String r : Text.apply(p, variabili, description)) {
                rows.add(senzaCorsivo(Colors.component(r)));
            }
            meta.lore(rows);
        }

        for (String scritto : def.incantesimi()) {
            incantesimo(meta, Text.raw(p, variabili, scritto), problemi);
        }
        if (def.luccica()) {
            // Prima si ottenevano i riflessi mettendo un incantesimo finto e nascondendolo con un
            // flag. Da 1.20.5 c'e' la chiave apposta: niente incantesimo di troppo da spiegare.
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
        }
        if (def.indistruttibile()) {
            meta.setUnbreakable(true);
        }
        if (def.hideDetails()) {
            meta.addItemFlags(ItemFlag.values());
        }

        if (def.modelloCustom() != null && !def.modelloCustom().isBlank()) {
            Double n = Text.number(Text.raw(p, variabili, def.modelloCustom()));
            if (n == null) {
                problemi.add("modello_custom non e' un numero: " + def.modelloCustom());
            } else {
                meta.setCustomModelData((int) (double) n);
            }
        }
        if (def.modelloItem() != null && !def.modelloItem().isBlank()) {
            NamespacedKey k = NamespacedKey.fromString(
                    Text.raw(p, variabili, def.modelloItem()).trim().toLowerCase(Locale.ROOT));
            if (k == null) {
                problemi.add("modello_item non e' un nome valido: " + def.modelloItem());
            } else {
                meta.setItemModel(k);
            }
        }

        if (def.color() != null && !def.color().isBlank()) {
            color(meta, Text.raw(p, variabili, def.color()), problemi);
        }
        if (testaRichiesta && meta instanceof SkullMeta teschio) {
            testa(teschio, Text.raw(p, variabili, def.testa()).trim(), problemi);
        }

        if (!problemi.isEmpty()) {
            // L'item si vede lo stesso, ma con i problemi scritti sopra: nascondere l'errore
            // significherebbe lasciare in giro un menu che sembra a posto e non lo e'.
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(senzaCorsivo(Colors.component("&#FF6B6B&lAttenzione")));
            for (String s : problemi) {
                lore.add(senzaCorsivo(Colors.component("&#FF6B6B• &7" + s)));
            }
            meta.lore(lore);
        }

        stack.setItemMeta(meta);
        return stack;
    }

    /** L'item che compare al posto di quello che non si e' potuto costruire. */
    public static ItemStack barriera(String name, List<String> motivi) {
        ItemStack stack = new ItemStack(Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(senzaCorsivo(Colors.component("&#FF6B6B&lItem con un errore &8· &7" + name)));
        List<Component> lore = new ArrayList<>();
        for (String m : motivi) {
            lore.add(senzaCorsivo(Colors.component("&#FF6B6B• &7" + m)));
        }
        lore.add(Component.empty());
        lore.add(senzaCorsivo(Colors.component("&8Correggi il file del menu e usa /menus reload")));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    // ---------------------------------------------------------------- i pezzi

    private static int quantita(Player p, Map<String, String> variabili, ItemDef def, Material m) {
        Double n = Text.number(Text.raw(p, variabili, def.quantita()));
        int q = n == null ? 1 : (int) (double) n;
        return Math.max(1, Math.min(q, Math.max(1, m.getMaxStackSize())));
    }

    private static ItemStack raw(Logger log, Player p, Map<String, String> variabili,
                                    ItemDef def, ItemStack stack, List<String> problemi) {
        String snbt = Text.raw(p, variabili, def.raw()).trim();
        if (snbt.isEmpty()) {
            return stack;
        }
        if (!snbt.startsWith("[") && !snbt.startsWith("{")) {
            snbt = "[" + snbt + "]";
        }
        try {
            return Bukkit.getUnsafe().modifyItemStack(stack, snbt);
        } catch (Throwable t) {
            // Il formato dei componenti cambia fra le versioni di Minecraft: un errore qui e'
            // quasi sempre un menu scritto per una versione precedente, e va detto per intero.
            problemi.add("il campo avanzate non e' stato accettato: " + t.getMessage());
            log.warning("[MagixMenus] item \"" + def.name() + "\": componenti non validi \"" + snbt + "\"");
            return stack;
        }
    }

    private static void incantesimo(ItemMeta meta, String scritto, List<String> problemi) {
        Matcher m = INCANTESIMO.matcher(scritto);
        String name;
        int livello = 1;
        if (m.matches()) {
            name = m.group(1);
            livello = Integer.parseInt(m.group(2));
        } else {
            name = scritto.trim();
        }
        if (name.isEmpty()) {
            return;
        }
        Enchantment e = Registry.ENCHANTMENT.match(name);
        if (e == null) {
            problemi.add("incantesimo sconosciuto: " + name);
            return;
        }
        meta.addEnchant(e, Math.max(1, livello), true);   // true: anche oltre il limite del gioco
    }

    private static void color(ItemMeta meta, String scritto, List<String> problemi) {
        Color c = readColor(scritto);
        if (c == null) {
            problemi.add("colore non valido: " + scritto + " (serve #RRGGBB o r,g,b)");
            return;
        }
        if (meta instanceof LeatherArmorMeta pelle) {
            pelle.setColor(c);
        } else if (meta instanceof PotionMeta pozione) {
            pozione.setColor(c);
        } else {
            problemi.add("questo item non si puo' colorare");
        }
    }

    private static Color readColor(String s) {
        String t = s.trim();
        try {
            if (t.startsWith("#")) {
                return Color.fromRGB(Integer.parseInt(t.substring(1), 16));
            }
            if (t.contains(",")) {
                String[] rgb = t.split(",");
                return Color.fromRGB(Integer.parseInt(rgb[0].trim()),
                        Integer.parseInt(rgb[1].trim()), Integer.parseInt(rgb[2].trim()));
            }
            return Color.fromRGB(Integer.parseInt(t, 16));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * La faccia sulla testa: un giocatore, un indirizzo di texture o il valore base64 che si
     * trova sui siti di teste.
     *
     * Per un giocatore non collegato NON si va a chiedere il profilo a Mojang: quella e' una
     * chiamata di rete, e farla mentre si disegna un menu bloccherebbe il server per tutti finche'
     * non risponde. Si usa quello che il server ha gia' in memoria; se non ce l'ha, la testa
     * comincia anonima e prende la faccia giusta appena il server risolve il profilo per conto suo.
     */
    private static void testa(SkullMeta meta, String value, List<String> problemi) {
        if (value.isEmpty()) {
            return;
        }
        if (value.startsWith("http://") || value.startsWith("https://")) {
            texture(meta, value, problemi);
            return;
        }
        if (value.length() > 60 && value.matches("[A-Za-z0-9+/=]+")) {
            try {
                String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                Matcher m = URL_TEXTURE.matcher(json);
                if (m.find()) {
                    texture(meta, m.group(1), problemi);
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // Non era base64: allora e' un nome di giocatore molto lungo, si prosegue sotto.
            }
        }

        Player online = Bukkit.getPlayerExact(value);
        if (online != null) {
            meta.setPlayerProfile(online.getPlayerProfile());
            return;
        }
        OfflinePlayer conosciuto = Bukkit.getOfflinePlayerIfCached(value);
        if (conosciuto != null) {
            meta.setOwningPlayer(conosciuto);
            return;
        }
        meta.setPlayerProfile(Bukkit.createProfile(value));
    }

    private static void texture(SkullMeta meta, String url, List<String> problemi) {
        try {
            PlayerProfile profilo = Bukkit.createProfile(UUID.randomUUID(), "");
            PlayerTextures texture = profilo.getTextures();
            texture.setSkin(URI.create(url).toURL());
            profilo.setTextures(texture);
            meta.setPlayerProfile(profilo);
        } catch (Exception e) {
            problemi.add("texture della testa non valida: " + url);
        }
    }

    /**
     * I nomi degli item in Minecraft sono in corsivo se non si dice il contrario, e nessuno lo
     * vuole in un menu.
     */
    private static Component senzaCorsivo(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }
}
