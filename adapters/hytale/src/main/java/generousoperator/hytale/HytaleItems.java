package generousoperator.hytale;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import generousoperator.give.Ports;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** The live item registry (modded items included), and putting items straight into a player's inventory. */
final class HytaleItems implements Ports.Items, Ports.Inventory {
    private final Consumer<String> serverLog;

    HytaleItems(Consumer<String> serverLog) {
        this.serverLog = serverLog;
    }

    private static Item item(String id) {
        try {
            return Item.getAssetMap() == null ? null : Item.getAssetMap().getAsset(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean exists(String itemId) {
        return item(itemId) != null;
    }

    @Override
    public String name(String itemId) {
        Item it = item(itemId);
        if (it == null || it.getTranslationKey() == null) return itemId;
        try {
            String name = I18nModule.get().getMessage(I18nModule.DEFAULT_LANGUAGE, it.getTranslationKey());
            return name == null || name.isBlank() ? itemId : name;
        } catch (RuntimeException e) {
            return itemId;
        }
    }

    /** Gives on the recipient's world thread, in stacks of the item's own size. What doesn't fit is reported, not dropped. */
    @Override
    public void give(String recipientId, Map<String, Long> items, Consumer<Delivered> done) {
        PlayerRef p = HytalePlayers.player(recipientId);
        Ref<EntityStore> ref = p == null ? null : p.getReference();
        if (ref == null || !ref.isValid()) {
            done.accept(new Delivered(Map.of(), "The recipient isn't in a world."));
            return;
        }
        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        world.execute(() -> {
            Map<String, Long> given = new LinkedHashMap<>();
            String problem = null;
            try {
                for (Map.Entry<String, Long> e : items.entrySet()) {
                    Item it = item(e.getKey());
                    if (it == null) {
                        problem = "There's no item " + e.getKey() + " any more.";
                        continue;
                    }
                    int max = Math.max(1, it.getMaxStack());
                    long left = e.getValue(), in = 0;
                    while (left > 0) {
                        int n = (int) Math.min(left, max);
                        ItemStack rest = Player.giveItem(new ItemStack(e.getKey(), n), ref, store).getRemainder();
                        int missed = rest == null || rest.isEmpty() ? 0 : rest.getQuantity();
                        in += n - missed;
                        left -= n;
                        if (missed > 0) {
                            problem = p.getUsername() + "'s inventory is full.";
                            break;
                        }
                    }
                    if (in > 0) given.put(e.getKey(), in);
                }
            } catch (RuntimeException ex) {
                serverLog.accept("Giving items to " + p.getUsername() + " failed: " + ex);
                problem = "The game refused the items: " + ex.getMessage();
            }
            done.accept(new Delivered(given, problem));
        });
    }
}
