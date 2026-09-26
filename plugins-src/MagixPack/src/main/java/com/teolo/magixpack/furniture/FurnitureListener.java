package com.teolo.magixpack.furniture;

import com.teolo.magixpack.item.ItemCatalog;
import com.teolo.magixpack.item.ItemEntry;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;

/**
 * Piazza per terra un oggetto custom con {@code furniture: true} in items.yml, con l'aspetto
 * (texture/modello) vero dell'oggetto — non un blocco vanilla travestito. Meccanica, come da
 * scelta esplicita: chiunque tenga in mano l'oggetto lo puo' piazzare (nessun permesso a parte);
 * si rompe attaccando l'entita' (niente proprietario/whitelist propri — la protezione la fa la
 * regione/claim gia' presente sul server, esattamente come per un blocco normale piazzato li'.
 * Limite noto e accettato: un plugin di protezione pensato per i BLOCCHI potrebbe non coprire
 * da solo un'entita' come questa).
 *
 * <h2>Come e' fatta una furniture piazzata</h2>
 * Una coppia di entita' nello stesso punto:
 * <ul>
 *   <li>{@link ItemDisplay} — solo l'aspetto (il modello/texture dell'oggetto, stesso ItemStack
 *       che {@code /mpack item give} darebbe), non e' cliccabile da solo;</li>
 *   <li>{@link Interaction} — invisibile, e' quella che player e altri plugin possono davvero
 *       colpire/cliccare, con una hitbox scelta da noi invece di quella (spesso scomoda) del
 *       modello.</li>
 * </ul>
 * Le due si tengono in coppia tramite l'UUID dell'altra scritto nel proprio {@link
 * org.bukkit.persistence.PersistentDataContainer} ({@link #pairedKey}), cosi' rompendo/rimuovendo
 * una si trova subito l'altra da togliere insieme. Se {@code furniture-solid} e' true nel
 * catalogo, si aggiunge anche un blocco {@link Material#LIGHT} di livello 0 (invisibile ma
 * solido) nella stessa posizione, per dare davvero collisione al passaggio — la sua posizione e'
 * salvata nello stesso modo, cosi' si toglie insieme al resto quando la furniture si rompe.
 */
public final class FurnitureListener implements Listener {

    private final JavaPlugin plugin;
    private final ItemCatalog itemCatalog;

    private final NamespacedKey markerKey;
    private final NamespacedKey pairedKey;
    private final NamespacedKey blockKey;
    private final NamespacedKey hitsKey;

    public FurnitureListener(JavaPlugin plugin, ItemCatalog itemCatalog) {
        this.plugin = plugin;
        this.itemCatalog = itemCatalog;
        this.markerKey = new NamespacedKey(ItemCatalog.NAMESPACE, "furniture-item");
        this.pairedKey = new NamespacedKey(ItemCatalog.NAMESPACE, "furniture-paired");
        this.blockKey = new NamespacedKey(ItemCatalog.NAMESPACE, "furniture-block");
        this.hitsKey = new NamespacedKey(ItemCatalog.NAMESPACE, "furniture-hits-taken");
    }

    /** Tasto destro sulla faccia SUPERIORE di un blocco, con in mano un oggetto {@code furniture:
     *  true}: lo piazza sopra, invece del normale utilizzo dell'oggetto/blocco. Solo la faccia
     *  superiore (mai i lati) apposta: su un terreno sconnesso (gradini da un blocco, tipici di
     *  badlands/spiagge...) cliccare il LATO di un blocco piu' basso farebbe nascere l'oggetto
     *  alla sua stessa altezza, piu' bassa del terreno intorno - sembrerebbe "affondato" nel
     *  terreno vicino, anche se il modello stesso (Blockbench) e' perfettamente centrato: non e'
     *  un problema di modello, e' la faccia sbagliata su cui appoggiarsi. Per default serve anche
     *  shift (evita conflitti con l'interazione normale, es. aprire un baule cliccato), ma e'
     *  configurabile per oggetto con {@code furniture-shift-required: false} in items.yml. */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = e.getPlayer();

        ItemStack hand = e.getItem();
        if (hand == null || hand.getItemMeta() == null) return;
        ItemMeta meta = hand.getItemMeta();
        String id = meta.getPersistentDataContainer().get(ItemCatalog.ITEM_ID_KEY, PersistentDataType.STRING);
        if (id == null) return;
        ItemEntry entry = itemCatalog.entry(id);
        if (entry == null || !entry.furniture()) return;
        if (entry.furnitureShiftRequired() && !player.isSneaking()) return;

        Block clicked = e.getClickedBlock();
        BlockFace face = e.getBlockFace();
        if (clicked == null || face != BlockFace.UP) return;
        Block target = clicked.getRelative(face);
        if (!target.getType().isAir()) return;

        e.setCancelled(true);

        World world = target.getWorld();
        Location loc = target.getLocation().add(0.5, 0, 0.5);
        float yaw = Math.round(player.getLocation().getYaw() / 90f) * 90f;
        loc.setYaw(yaw);

        ItemStack displayed = itemCatalog.build(id);
        if (displayed == null) return;

        ItemDisplay display = world.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(displayed);
            // NONE, non FIXED: FIXED applica la trasformazione vanilla pensata per un'icona 2D
            // dentro un item frame (pensata per un layer0 piatto, non per un modello 3D vero) e
            // distorce/appiattisce un modello come questo. NONE mostra il modello grezzo, con solo
            // la Transformation scelta qui sotto.
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new Transformation(
                    // Un ItemDisplay ancora il modello al CENTRO nominale del proprio spazio (non
                    // al fondo, come farebbe un blocco), verificato via log su un piazzamento reale.
                    // furnitureYOffset e' calcolato da ItemCatalog dalla vera altezza del modello
                    // (letta dal suo JSON), non un fisso 0.5: un cubo piu' basso di un blocco intero
                    // (es. elements 4-12) andrebbe sollevato di meno, altrimenti resta sospeso.
                    new Vector3f(0f, (float) entry.furnitureYOffset(), 0f),
                    new Quaternionf(new AxisAngle4f((float) Math.toRadians(yaw), 0f, 1f, 0f)),
                    new Vector3f(1f, 1f, 1f),
                    new Quaternionf()));
        });
        Interaction interaction = world.spawn(loc, Interaction.class, i -> {
            i.setInteractionWidth(0.9f);
            i.setInteractionHeight(0.9f);
            i.setResponsive(true);
        });
        display.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, id);
        display.getPersistentDataContainer().set(pairedKey, PersistentDataType.STRING, interaction.getUniqueId().toString());
        interaction.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, id);
        interaction.getPersistentDataContainer().set(pairedKey, PersistentDataType.STRING, display.getUniqueId().toString());

        if (entry.furnitureSolid()) {
            target.setType(Material.LIGHT);
            if (target.getBlockData() instanceof Levelled levelled) {
                levelled.setLevel(0);
                target.setBlockData(levelled);
            }
            String blockLoc = target.getX() + "," + target.getY() + "," + target.getZ();
            display.getPersistentDataContainer().set(blockKey, PersistentDataType.STRING, blockLoc);
            interaction.getPersistentDataContainer().set(blockKey, PersistentDataType.STRING, blockLoc);
        }

        if (player.getGameMode() != GameMode.CREATIVE) {
            if (hand.getAmount() <= 1) {
                player.getInventory().setItemInMainHand(null);
            } else {
                hand.setAmount(hand.getAmount() - 1);
            }
        }
    }

    /** Attaccare l'entita' Interaction di una furniture la colpisce (nessun tasto/permesso a
     *  parte, come un blocco: chi puo' colpirla per la protezione della regione/claim, puo'
     *  romperla). Serve {@code furniture-hits} colpi (contati sull'entita' stessa, non sul
     *  catalogo: due copie piazzate della stessa furniture si rompono in modo indipendente) prima
     *  di rompersi davvero — un colpo che non basta ancora suona solo {@code furniture-hit-sound}
     *  e memorizza il progresso, l'ultimo suona anche {@code furniture-break-sound} e toglie la
     *  coppia Display+Interaction (piu' il blocco di collisione, se c'era). {@code furniture-drop}
     *  decide cosa fare dell'oggetto all'ultimo colpo: darlo a chi ha colpito (default) o farlo
     *  cadere per terra come un blocco normale. */
    @EventHandler(ignoreCancelled = true)
    public void onBreak(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Interaction interaction)) return;
        if (!(e.getDamager() instanceof Player player)) return;
        String id = interaction.getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);

        ItemEntry entry = itemCatalog.entry(id);
        int hitsNeeded = entry != null ? Math.max(1, entry.furnitureHits()) : 1;
        Sound hitSound = entry != null ? entry.furnitureHitSound() : Sound.BLOCK_WOOD_HIT;
        Sound breakSound = entry != null ? entry.furnitureBreakSound() : Sound.BLOCK_WOOD_BREAK;
        boolean drop = entry != null && entry.furnitureDrop();

        Location loc = interaction.getLocation();
        int hitsTaken = interaction.getPersistentDataContainer()
                .getOrDefault(hitsKey, PersistentDataType.INTEGER, 0) + 1;
        interaction.getWorld().playSound(loc, hitSound, 1f, 1f);
        if (hitsTaken < hitsNeeded) {
            interaction.getPersistentDataContainer().set(hitsKey, PersistentDataType.INTEGER, hitsTaken);
            return;
        }
        interaction.getWorld().playSound(loc, breakSound, 1f, 1f);

        String pairedUuid = interaction.getPersistentDataContainer().get(pairedKey, PersistentDataType.STRING);
        if (pairedUuid != null) {
            Entity paired = Bukkit.getEntity(UUID.fromString(pairedUuid));
            if (paired != null) paired.remove();
        }
        String blockLoc = interaction.getPersistentDataContainer().get(blockKey, PersistentDataType.STRING);
        if (blockLoc != null) {
            String[] parts = blockLoc.split(",");
            Block block = interaction.getWorld().getBlockAt(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            if (block.getType() == Material.LIGHT) block.setType(Material.AIR);
        }
        interaction.remove();

        ItemStack item = itemCatalog.build(id);
        if (item == null) return;
        if (drop) {
            loc.getWorld().dropItemNaturally(loc, item);
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack extra : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), extra);
        }
    }
}
