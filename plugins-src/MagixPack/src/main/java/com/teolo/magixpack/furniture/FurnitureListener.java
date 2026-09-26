package com.teolo.magixpack.furniture;

import com.teolo.magixpack.item.ItemCatalog;
import com.teolo.magixpack.item.ItemEntry;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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

    public FurnitureListener(JavaPlugin plugin, ItemCatalog itemCatalog) {
        this.plugin = plugin;
        this.itemCatalog = itemCatalog;
        this.markerKey = new NamespacedKey(ItemCatalog.NAMESPACE, "furniture-item");
        this.pairedKey = new NamespacedKey(ItemCatalog.NAMESPACE, "furniture-paired");
        this.blockKey = new NamespacedKey(ItemCatalog.NAMESPACE, "furniture-block");
    }

    /** Tasto destro su un blocco, con in mano un oggetto {@code furniture: true}: lo piazza sulla
     *  faccia cliccata, invece del normale utilizzo dell'oggetto/blocco. Per default serve anche
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
        if (clicked == null || face == null) return;
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
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setTransformation(new Transformation(
                    new Vector3f(0f, 0f, 0f),
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

    /** Attaccare l'entita' Interaction di una furniture la rompe (nessun tasto/permesso a parte,
     *  come un blocco: chi puo' colpirla per la protezione della regione/claim, puo' romperla) —
     *  toglie la coppia Display+Interaction, il blocco di collisione se c'era, e ridà l'oggetto. */
    @EventHandler(ignoreCancelled = true)
    public void onBreak(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Interaction interaction)) return;
        if (!(e.getDamager() instanceof Player player)) return;
        String id = interaction.getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);

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
        if (item != null) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            for (ItemStack extra : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), extra);
            }
        }
    }
}
