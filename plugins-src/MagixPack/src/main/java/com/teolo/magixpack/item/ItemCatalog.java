package com.teolo.magixpack.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.teolo.magixpack.avatar.AvatarService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerTextures;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Legge {@code items.yml} (creata vuota al primo avvio, la riempie lo staff) e ne ricava due cose:
 *
 * <ul>
 *   <li>il contenuto da registrare nel resource pack — un modello 2D generato ({@code
 *       assets/magixpack/models/item/<id>.json}) che punta alla texture che lo staff ha messo in
 *       {@code items/<id>.png} — vedi {@link #packFiles()};</li>
 *   <li>l'{@link ItemStack} vero da dare a un giocatore — vedi {@link #build}.</li>
 * </ul>
 *
 * <p>Un oggetto senza la sua texture in {@code items/<id>.png} viene ignorato (con un avviso nel
 * log): niente modello rotto nel pacchetto, niente item fantasma da poter dare.
 *
 * <h2>Modelli 3D</h2>
 * Il layer0 2D generato e' solo il caso semplice/predefinito. Mettendo un vero modello Minecraft
 * (con {@code "elements"}/{@code "faces"}, tipico export da Blockbench o scritto a mano) in
 * {@code items/<id>-model.json}, quel file viene usato COSI' COM'E' al posto della generazione
 * automatica — stesso principio di Oraxen ({@code generate_model: false, model: ...}). La texture
 * in {@code items/<id>.png} resta comunque obbligatoria (referenziata dal modello tramite
 * {@code magixpack:item/<id>}).
 */
public final class ItemCatalog {

    /** Namespace proprio di MagixPack nel pacchetto: mai "minecraft" per un file PROPRIO (per
     *  sostituire una texture vanilla c'e' overrides/, esplicito) — l'unica eccezione voluta e'
     *  {@link #packFiles()}, che tocca DELIBERATAMENTE il modello vanilla dell'item base scelto,
     *  per il meccanismo di compatibilita' col CustomModelData "vecchio" (vedi la Javadoc li'). */
    public static final String NAMESPACE = "magixpack";

    /** Primo valore di CustomModelData assegnato: alto apposta per non entrare in conflitto con
     *  un valore che un altro plugin/comando avesse gia' scelto per lo stesso material. */
    private static final int FIRST_CUSTOM_MODEL_DATA = 3_100_000;

    /** Chiave PDC scritta su ogni {@link ItemStack} costruito da {@link #build}: l'id del
     *  catalogo, cosi' {@code furniture.FurnitureListener} puo' risalire alla voce di items.yml
     *  (e quindi al flag {@code furniture}) da un ItemStack in mano, senza dover confrontare
     *  material/nome/lore (fragile: due oggetti diversi possono condividere lo stesso material). */
    public static final NamespacedKey ITEM_ID_KEY = new NamespacedKey(NAMESPACE, "item-id");

    /** {@code type} of an items.yml entry built per player: head + avatar (skin face glyph) in the lore. */
    public static final String TYPE_PLAYER_AVATAR = "player-avatar";

    private final JavaPlugin plugin;
    private final AvatarService avatars;
    private final Map<String, ItemEntry> entries = new LinkedHashMap<>();

    public ItemCatalog(JavaPlugin plugin, AvatarService avatars) {
        this.plugin = plugin;
        this.avatars = avatars;
    }

    /** Rilegge items.yml e la cartella items/ (texture) dal disco. */
    public void reload() {
        entries.clear();
        File file = new File(plugin.getDataFolder(), "items.yml");
        if (!file.exists()) plugin.saveResource("items.yml", false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        // CustomModelData assegnato in ordine alfabetico sugli id validi, come il codepoint dei
        // glifi (vedi glyph.GlyphCatalog): deterministico, ma puo' cambiare se il catalogo cambia.
        int nextCustomModelData = FIRST_CUSTOM_MODEL_DATA;
        for (String id : new java.util.TreeSet<>(cfg.getKeys(false))) {
            ConfigurationSection sec = cfg.getConfigurationSection(id);
            if (sec == null) continue;
            if (TYPE_PLAYER_AVATAR.equalsIgnoreCase(sec.getString("type", ""))) {
                // No texture and no model: the look comes from the player's skin, at build time.
                entries.put(id, new ItemEntry(id, Material.PLAYER_HEAD.name(), sec.getString("name", "{player}"),
                        sec.getStringList("lore"), 0, false, false, true, 0.5, 1, false,
                        Sound.BLOCK_WOOD_HIT, Sound.BLOCK_WOOD_BREAK, true));
                continue;
            }
            String materialName = sec.getString("material", "").trim().toUpperCase(java.util.Locale.ROOT);
            Material material = Material.matchMaterial(materialName);
            if (material == null || material.isAir()) {
                plugin.getLogger().warning("[Items] '" + id + "' in items.yml: material '" + materialName
                        + "' non esiste, oggetto ignorato.");
                continue;
            }
            if (!textureFile(id).isFile()) {
                plugin.getLogger().warning("[Items] '" + id + "' in items.yml: manca la texture items/"
                        + id + ".png, oggetto ignorato.");
                continue;
            }
            String name = sec.getString("name", id);
            List<String> lore = sec.getStringList("lore");
            boolean furniture = sec.getBoolean("furniture", false);
            boolean furnitureSolid = sec.getBoolean("furniture-solid", false);
            boolean furnitureShiftRequired = sec.getBoolean("furniture-shift-required", true);
            double furnitureYOffset = furniture ? furnitureYOffset(id) : 0.5;
            int furnitureHits = Math.max(1, sec.getInt("furniture-hits", 1));
            boolean furnitureDrop = sec.getBoolean("furniture-drop", false);
            Sound furnitureHitSound = parseSound(sec.getString("furniture-hit-sound", "BLOCK_WOOD_HIT"),
                    id, "furniture-hit-sound", Sound.BLOCK_WOOD_HIT);
            Sound furnitureBreakSound = parseSound(sec.getString("furniture-break-sound", "BLOCK_WOOD_BREAK"),
                    id, "furniture-break-sound", Sound.BLOCK_WOOD_BREAK);
            entries.put(id, new ItemEntry(id, materialName, name, lore, nextCustomModelData,
                    furniture, furnitureSolid, furnitureShiftRequired, furnitureYOffset,
                    furnitureHits, furnitureDrop, furnitureHitSound, furnitureBreakSound, false));
            nextCustomModelData++;
        }
        if (!entries.isEmpty()) {
            plugin.getLogger().info("[Items] " + entries.size() + " oggetto/i custom caricati da items.yml.");
        }
    }

    private File textureFile(String id) {
        return new File(new File(plugin.getDataFolder(), "items"), id + ".png");
    }

    /** Modello 3D "grezzo" facoltativo: se lo staff (o chi carica la texture) mette
     *  {@code items/<id>-model.json} — un vero file di modello Minecraft, con "elements" e
     *  "faces", tipico export da Blockbench o scritto a mano — viene usato COSI' COM'E' al posto
     *  del semplice layer0 2D generato di default. Stesso principio di Oraxen ("generate_model:
     *  false, model: ..."): l'auto-generazione resta solo per il caso semplice (icona piatta). */
    private File customModelFile(String id) {
        return new File(new File(plugin.getDataFolder(), "items"), id + "-model.json");
    }

    /** Il modello vero e proprio per {@code id}: il file {@code items/<id>-model.json} cosi'
     *  com'e' se esiste, altrimenti il semplice layer0 2D generato di default. Usato sia da
     *  {@link #packFiles()} (cosa va nel pacchetto) sia da {@link #furnitureYOffset} (dove sta
     *  davvero il fondo del modello) — un solo posto che decide "qual e' il modello di questo
     *  oggetto", non due copie della stessa scelta. */
    private byte[] resolveModelBytes(String id) throws IOException {
        File customModel = customModelFile(id);
        if (customModel.isFile()) return Files.readAllBytes(customModel.toPath());
        return ("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\""
                + NAMESPACE + ":item/" + id + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    /** Quanto spostare in alto (in blocchi) un {@link org.bukkit.entity.ItemDisplay} che mostra
     *  questo oggetto come furniture, perche' il suo fondo tocchi il punto di piazzamento invece
     *  di restarci sospeso a meta': un ItemDisplay ancora il modello al CENTRO nominale del
     *  proprio spazio locale (elemento y=8 su 16), non al fondo come farebbe un blocco — verificato
     *  con un log su un piazzamento reale (vedi il commit che ha introdotto questo metodo). Per un
     *  modello a tutta altezza (0-16, es. il layer0 2D generato) il fondo e' y=0 e lo spostamento
     *  giusto e' 0.5 blocchi; per un modello piu' piccolo o non centrato (es. un cubo 4-12) va
     *  ricalcolato dal suo vero punto piu' basso, letto dal JSON invece di indovinato — cosi'
     *  qualunque modello 3D futuro (Blockbench o scritto a mano) si appoggia da solo, senza dover
     *  regolare a mano un valore per ogni oggetto. */
    private double furnitureYOffset(String id) {
        try {
            JsonObject model = JsonParser.parseString(new String(resolveModelBytes(id), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray elements = model.getAsJsonArray("elements");
            if (elements == null || elements.isEmpty()) return 0.5;
            double minY = Double.MAX_VALUE;
            for (JsonElement el : elements) {
                JsonObject e = el.getAsJsonObject();
                double fromY = e.getAsJsonArray("from").get(1).getAsDouble();
                double toY = e.getAsJsonArray("to").get(1).getAsDouble();
                minY = Math.min(minY, Math.min(fromY, toY));
            }
            return (8.0 - minY) / 16.0;
        } catch (RuntimeException | IOException ex) {
            plugin.getLogger().warning("[Items] '" + id + "': impossibile leggere il modello per calcolare "
                    + "l'altezza della furniture (" + ex.getMessage() + "), uso 0.5 blocchi di default.");
            return 0.5;
        }
    }

    /** Un nome di {@link Sound} scritto dallo staff in items.yml, con un default sensato se manca
     *  o e' scritto male (avviso in console, non un oggetto ignorato: il suono e' solo un
     *  dettaglio, non deve bloccare tutto il resto della voce). */
    private Sound parseSound(String raw, String id, String key, Sound fallback) {
        try {
            return Sound.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("[Items] '" + id + "' in items.yml: " + key + " '" + raw
                    + "' non esiste, uso " + fallback + ".");
            return fallback;
        }
    }

    public boolean has(String id) {
        return entries.containsKey(id);
    }

    public List<String> ids() {
        return new ArrayList<>(new TreeMap<>(entries).keySet());
    }

    /** La voce del catalogo per {@code id} (flag furniture inclusi), o null se non c'e'. */
    public ItemEntry entry(String id) {
        return entries.get(id);
    }

    /** L'oggetto vero, pronto da dare: null se l'id non esiste nel catalogo. */
    public ItemStack build(String id) {
        return build(id, null);
    }

    /** Like {@link #build(String)}; {@code owner} is the player a {@code player-avatar} item is
     *  about (ignored by the other items). */
    public ItemStack build(String id, Player owner) {
        ItemEntry e = entries.get(id);
        if (e == null) return null;
        if (e.playerAvatar()) return buildAvatar(e, owner);
        Material material = Material.matchMaterial(e.material());
        if (material == null) return null;
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            // ATTENZIONE alla differenza: il componente item_model punta al file di DEFINIZIONE
            // (assets/<ns>/items/<id>.json, SENZA "item/"), non al modello (assets/<ns>/models/
            // item/<id>.json, quello si') — bug reale, trovato perche' con "item/" qui il client
            // cercava assets/magixpack/items/item/test_gem.json (inesistente) e falliva in
            // silenzio mostrando la texture "mancante", pur avendo model.json e texture corretti.
            meta.setItemModel(new NamespacedKey(NAMESPACE, id));
            // Anche il meccanismo "vecchio" (CustomModelData + predicate override sul modello
            // vanilla, vedi packFiles()): tenerli insieme copre entrambe le strade con cui il
            // client potrebbe risolvere l'aspetto dell'oggetto, come raccomanda Oraxen stesso.
            meta.setCustomModelData(e.customModelData());
            // Cosi' furniture.FurnitureListener sa quale voce di items.yml corrisponde a questo
            // ItemStack (id del catalogo, non material/nome: due oggetti possono condividerli).
            meta.getPersistentDataContainer().set(ITEM_ID_KEY, PersistentDataType.STRING, id);
            if (e.name() != null && !e.name().isBlank()) {
                meta.displayName(com.teolo.magixpack.util.Colors.component(e.name())
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            }
            if (!e.lore().isEmpty()) {
                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                for (String riga : e.lore()) {
                    lore.add(com.teolo.magixpack.util.Colors.component(riga)
                            .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
                }
                meta.lore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * A {@code player-avatar} item: the owner's head as icon and their avatar (the flat face of
     * the skin, as a glyph) at the bottom of the lore, visible on hover. {@code {player}} in name and lore
     * becomes the owner's name. If the avatar is not downloaded yet the lore has no face (the
     * download starts now: an item built a moment later has it).
     */
    private ItemStack buildAvatar(ItemEntry e, Player owner) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        if (!(stack.getItemMeta() instanceof SkullMeta meta)) return stack;
        String name = owner != null ? owner.getName() : "";
        meta.getPersistentDataContainer().set(ITEM_ID_KEY, PersistentDataType.STRING, e.id());
        if (owner != null) meta.setPlayerProfile(profileWithSkin(owner));
        if (e.name() != null && !e.name().isBlank()) {
            meta.displayName(com.teolo.magixpack.util.Colors.component(e.name().replace("{player}", name))
                    .decoration(TextDecoration.ITALIC, false));
        }
        List<Component> lore = new ArrayList<>();
        for (String riga : e.lore()) {
            lore.add(com.teolo.magixpack.util.Colors.component(riga.replace("{player}", name))
                    .decoration(TextDecoration.ITALIC, false));
        }
        Component avatar = owner != null ? avatars.cached(owner) : null;
        if (avatar != null) {
            // Only a taller avatar (pixel-size > 1) sticks out: empty lines keep it in the tooltip.
            for (int i = 0; i < avatars.emptyLinesAbove(10); i++) lore.add(Component.empty());
            lore.add(avatar.decoration(TextDecoration.ITALIC, false));
        }
        if (!lore.isEmpty()) meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    /** The owner's profile, with the skin found by the avatar service when the profile has none
     *  (offline mode): otherwise the head would show the default skin. */
    private PlayerProfile profileWithSkin(Player owner) {
        PlayerProfile profile = owner.getPlayerProfile().clone();
        if (profile.getTextures().getSkin() != null) return profile;
        AvatarService.Skin skin = avatars.skin(owner.getName());
        if (skin == null) return profile;
        try {
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(URI.create(skin.url()).toURL(),
                    skin.slim() ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC);
            profile.setTextures(textures);
        } catch (Exception ex) {
            plugin.getLogger().warning("[Items] Skin di " + owner.getName() + " non applicata alla testa: " + ex.getMessage());
        }
        return profile;
    }

    /**
     * Contenuto da registrare nel pacchetto, per ogni oggetto valido, sotto il namespace proprio
     * {@value #NAMESPACE}:
     * <ul>
     *   <li>la texture, letta da {@code items/<id>.png};</li>
     *   <li>il MODELLO, {@code assets/magixpack/models/item/<id>.json} — un semplice layer0
     *       generato (come le icone 2D vanilla, {@code parent: item/generated}), OPPURE, se esiste
     *       {@code items/<id>-model.json}, quel file preso cosi' com'e' (modello 3D vero, vedi la
     *       Javadoc della classe);</li>
     *   <li>la DEFINIZIONE dell'oggetto, {@code assets/magixpack/items/<id>.json} — il file che
     *       {@link ItemStack#getItemMeta()}'s {@code setItemModel(NamespacedKey)} (il componente
     *       {@code item_model}, non il vecchio {@code CustomModelData}) va davvero a risolvere: da
     *       quando i modelli item sono passati al sistema a componenti, il model.json da solo non
     *       basta piu', ci vuole questo secondo file che lo richiama. Senza, il client mostra la
     *       texture "mancante" (il pattern viola/nero a scacchi) anche se model e texture sono nel
     *       pacchetto e si scaricano bene — e' esattamente il sintomo con cui e' stato scoperto.</li>
     *   <li>l'OVERRIDE del modello vanilla dell'item base (es. {@code
     *       assets/minecraft/models/item/paper.json}), col predicate {@code custom_model_data} di
     *       ogni oggetto che usa quel material: e' il meccanismo "vecchio", tenuto in PIU' del
     *       componente item_model per la stessa ragione, vedi la Javadoc di {@link #NAMESPACE}. Il
     *       file vanilla viene ricostruito per intero (non e' un merge: il client prende l'intero
     *       file dal pacchetto con priorita' piu' alta, non lo fonde) — sicuro solo per material
     *       "semplici" (icona 2D piatta, {@code parent: item/generated}, un solo layer): e' lo
     *       stesso limite di items.yml, vedi il README.</li>
     * </ul>
     */
    public Map<String, byte[]> packFiles() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        Map<String, List<ItemEntry>> byMaterial = new LinkedHashMap<>();
        for (ItemEntry e : entries.values()) {
            if (e.playerAvatar()) continue;
            try {
                byte[] texture = Files.readAllBytes(textureFile(e.id()).toPath());
                out.put("assets/" + NAMESPACE + "/textures/item/" + e.id() + ".png", texture);
                out.put("assets/" + NAMESPACE + "/models/item/" + e.id() + ".json", resolveModelBytes(e.id()));
                String definition = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\""
                        + NAMESPACE + ":item/" + e.id() + "\"}}";
                out.put("assets/" + NAMESPACE + "/items/" + e.id() + ".json",
                        definition.getBytes(StandardCharsets.UTF_8));
                byMaterial.computeIfAbsent(e.material(), k -> new ArrayList<>()).add(e);
            } catch (IOException ex) {
                plugin.getLogger().warning("[Items] Impossibile leggere items/" + e.id() + ".png ("
                        + ex.getMessage() + "): oggetto escluso da questo pacchetto.");
            }
        }
        for (Map.Entry<String, List<ItemEntry>> group : byMaterial.entrySet()) {
            String vanillaId = group.getKey().toLowerCase(java.util.Locale.ROOT);
            StringBuilder overrides = new StringBuilder();
            for (int i = 0; i < group.getValue().size(); i++) {
                ItemEntry e = group.getValue().get(i);
                if (i > 0) overrides.append(',');
                overrides.append("{\"predicate\":{\"custom_model_data\":").append(e.customModelData())
                        .append("},\"model\":\"").append(NAMESPACE).append(":item/").append(e.id()).append("\"}");
            }
            String legacyModel = "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"minecraft:item/"
                    + vanillaId + "\"},\"overrides\":[" + overrides + "]}";
            out.put("assets/minecraft/models/item/" + vanillaId + ".json",
                    legacyModel.getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }
}
