package generousoperator.api;

import java.util.List;

/**
 * A giftable kit, as callers and the window see it.
 *
 * @param kit the qualified id ({@code <source>/<id>})
 * @param form {@code "preset"} (a CommandDeck preset: as a person only) or {@code "items"} (an item list, which may roll)
 * @param source the mod that shipped it, or {@code server}
 */
public record KitInfo(String kit, String name, String description, String form, String source, String group,
                      String kind, Integer tier, String occasion, String biome, List<String> tags,
                      boolean oncePerPlayer, boolean limits) {
    public KitInfo {
        tags = List.copyOf(tags);
    }
}
