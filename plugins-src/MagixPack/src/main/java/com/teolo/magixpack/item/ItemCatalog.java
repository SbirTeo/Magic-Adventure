package com.teolo.magixpack.item;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
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

    private final JavaPlugin plugin;
    private final Map<String, ItemEntry> entries = new LinkedHashMap<>();

    public ItemCatalog(JavaPlugin plugin) {
        this.plugin = plugin;
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
            entries.put(id, new ItemEntry(id, materialName, name, lore, nextCustomModelData));
            nextCustomModelData++;
        }
        if (!entries.isEmpty()) {
            plugin.getLogger().info("[Items] " + entries.size() + " oggetto/i custom caricati da items.yml.");
        }
    }

    private File textureFile(String id) {
        return new File(new File(plugin.getDataFolder(), "items"), id + ".png");
    }

    public boolean has(String id) {
        return entries.containsKey(id);
    }

    public List<String> ids() {
        return new ArrayList<>(new TreeMap<>(entries).keySet());
    }

    /** L'oggetto vero, pronto da dare: null se l'id non esiste nel catalogo. */
    public ItemStack build(String id) {
        ItemEntry e = entries.get(id);
        if (e == null) return null;
        Material material = Material.matchMaterial(e.material());
        if (material == null) return null;
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setItemModel(new NamespacedKey(NAMESPACE, "item/" + id));
            // Anche il meccanismo "vecchio" (CustomModelData + predicate override sul modello
            // vanilla, vedi packFiles()): tenerli insieme copre entrambe le strade con cui il
            // client potrebbe risolvere l'aspetto dell'oggetto, come raccomanda Oraxen stesso.
            meta.setCustomModelData(e.customModelData());
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
     * Contenuto da registrare nel pacchetto, per ogni oggetto valido, sotto il namespace proprio
     * {@value #NAMESPACE}:
     * <ul>
     *   <li>la texture, letta da {@code items/<id>.png};</li>
     *   <li>il MODELLO generato (un semplice layer0, come le icone 2D vanilla — {@code parent:
     *       item/generated}), {@code assets/magixpack/models/item/<id>.json};</li>
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
            try {
                byte[] texture = Files.readAllBytes(textureFile(e.id()).toPath());
                out.put("assets/" + NAMESPACE + "/textures/item/" + e.id() + ".png", texture);
                String model = "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\""
                        + NAMESPACE + ":item/" + e.id() + "\"}}";
                out.put("assets/" + NAMESPACE + "/models/item/" + e.id() + ".json",
                        model.getBytes(StandardCharsets.UTF_8));
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
