package generousoperator.give;

import java.util.HashMap;
import java.util.Map;

/**
 * The power curve as a table of item zones: an item's power is the earliest world zone it can be had in (0 for
 * the oceans, then 1 to 4), from the shared generated curve ({@code power.json}), which the build turns into
 * this table. Only items the curve rates are in it; every other item, modded ones included, is unrated.
 *
 * <p>The text is a header line, {@code generousoperator.power<TAB>1<TAB><where it came from>}, then one
 * {@code <item id><TAB><zone>} line per item. Any other header means a layout this version can't read, so
 * every item is unrated rather than misread.
 */
public final class ZoneTable implements Ports.PowerCurve {
    public static final String FORMAT = "generousoperator.power";
    public static final String VERSION = "1";

    private final Map<String, Integer> zones;
    private final String origin;

    private ZoneTable(Map<String, Integer> zones, String origin) {
        this.zones = zones;
        this.origin = origin;
    }

    /** Reads the table; null if the header isn't this format and version. Bad lines are skipped. */
    public static ZoneTable parse(String text) {
        String[] lines = text.split("\r?\n");
        if (lines.length == 0) return null;
        String[] head = lines[0].split("\t", 3);
        if (head.length < 2 || !head[0].equals(FORMAT) || !head[1].equals(VERSION)) return null;
        Map<String, Integer> zones = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\t");
            if (f.length != 2 || f[0].isEmpty()) continue;
            try {
                int z = Integer.parseInt(f[1].trim());
                if (z >= 0) zones.put(f[0], z);
            } catch (NumberFormatException e) {
                // skipped: a damaged line rates nothing
            }
        }
        return new ZoneTable(zones, head.length > 2 ? head[2] : "");
    }

    /** The zone, whatever the count (how quantity raises power is still open). */
    @Override
    public Integer power(String itemId, long count) {
        return zones.get(itemId);
    }

    public int size() {
        return zones.size();
    }

    /** Where the table came from, as its header says ("ume.hytale.power 1, Hytale 0.7.4"). */
    public String origin() {
        return origin;
    }
}
