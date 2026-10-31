package generousoperator.give;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The operator's settings that decide who may give what. Edited through Mod Settings Manager; every change
 * applies at once (the adapter builds a new {@code Rules} and hands it to {@link GiftService#rules}).
 *
 * @param giverDailyCap gifts one giver may give per day; 0 for no limit
 * @param recipientDailyCap gifts one player may receive per day; 0 for no limit
 * @param pairCooldown the least time between two gifts from the same giver to the same recipient; zero for none
 * @param maxDepth the deepest table nesting a catalog may use
 * @param maxItemsPerRoll the most items one roll may give, counted one by one
 * @param anonymousGivers recipients see "An operator" instead of the giver's name (the log keeps the real name)
 * @param announce tell everyone online about each gift
 * @param trusted which mods may give whose kits from the server; the default (empty) denies every server present
 */
public record Rules(int giverDailyCap, int recipientDailyCap, Duration pairCooldown, int maxDepth, long maxItemsPerRoll,
                   boolean anonymousGivers, boolean announce, List<Trust> trusted) {
    public static final Rules DEFAULT = new Rules(0, 0, Duration.ZERO, 8, 10_000, false, false, List.of());

    public Rules {
        trusted = List.copyOf(trusted);
    }

    /**
     * Lets one calling mod give, from the server, item-list kits whose every reachable source is in
     * {@code kitSources}, up to {@code maxPower} on the best roll. Kits the power curve can't rate need explicit
     * approval: their qualified id in {@code unratedKits}, or their source in {@code unratedSources}.
     */
    public record Trust(String caller, Set<String> kitSources, int maxPower, Set<String> unratedKits, Set<String> unratedSources) {
        public Trust {
            kitSources = Set.copyOf(kitSources);
            unratedKits = Set.copyOf(unratedKits);
            unratedSources = Set.copyOf(unratedSources);
        }
    }
}
