package generousoperator.catalog;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an item-list kit may contain, without rolling it: for each item, the amount every roll gives (min) and
 * the most the best possible roll gives (max), plus every source mod whose tables the roll can reach.
 *
 * <p>The max is an upper bound: it assumes every chance line comes up and every pick lands on the choice with
 * the most of that item. Power is rated on it ("a 1% chance of Adamantite counts as Adamantite"), and server
 * present trust has to cover every source in {@link #sources}.
 */
public final class Contents {
    /** {@code min} is what every roll gives; {@code max} what the best roll can give. */
    public record Range(long min, long max) {
    }

    private final Map<String, Range> items;
    private final Set<String> sources;

    private Contents(Map<String, Range> items, Set<String> sources) {
        this.items = items;
        this.sources = sources;
    }

    public Map<String, Range> items() {
        return items;
    }

    /** The kit's own source and the source of every table it can reach. */
    public Set<String> sources() {
        return sources;
    }

    public static Contents of(Kit kit, Catalog catalog) {
        if (kit.lines() == null) throw new IllegalArgumentException(kit.qualifiedId() + " is a command preset, not an item list");
        Map<String, Contents> memo = new HashMap<>();
        return of(kit, catalog, memo, 0);
    }

    private static Contents of(Kit kit, Catalog catalog, Map<String, Contents> memo, int depth) {
        Contents known = memo.get(kit.qualifiedId());
        if (known != null) return known;
        Map<String, Range> items = new LinkedHashMap<>();
        Set<String> sources = new LinkedHashSet<>();
        sources.add(kit.source());
        for (Table.Line line : kit.lines()) {
            if (line instanceof Table.ChanceLine) {
                Table.ChanceLine c = (Table.ChanceLine) line;
                Contents o = outcome(c.outcome(), catalog, memo, depth);
                boolean sure = c.percent() >= 100;
                for (Map.Entry<String, Range> e : o.items.entrySet()) add(items, e.getKey(), sure ? e.getValue().min() : 0, e.getValue().max());
                sources.addAll(o.sources);
            } else {
                Table.PickLine p = (Table.PickLine) line;
                long picks = p.count().max();
                List<Table.Choice> from = p.from();
                Map<String, long[]> perItem = new LinkedHashMap<>();   // item -> the max each choice gives
                for (int i = 0; i < from.size(); i++) {
                    Contents o = outcome(from.get(i).outcome(), catalog, memo, depth);
                    sources.addAll(o.sources);
                    for (Map.Entry<String, Range> e : o.items.entrySet()) {
                        perItem.computeIfAbsent(e.getKey(), k -> new long[from.size()])[i] = e.getValue().max();
                    }
                }
                for (Map.Entry<String, long[]> e : perItem.entrySet()) {
                    long[] m = e.getValue().clone();
                    Arrays.sort(m);
                    long best = 0;
                    if (p.repeat()) {
                        best = m[m.length - 1] * picks;
                    } else {
                        for (int k = 0; k < picks && k < m.length; k++) best += m[m.length - 1 - k];
                    }
                    add(items, e.getKey(), 0, best);
                }
            }
        }
        Contents c = new Contents(items, sources);
        memo.put(kit.qualifiedId(), c);
        return c;
    }

    private static Contents outcome(Table.Outcome o, Catalog catalog, Map<String, Contents> memo, int depth) {
        Map<String, Range> items = new LinkedHashMap<>();
        Set<String> sources = new LinkedHashSet<>();
        for (Table.ItemCount ic : o.items()) add(items, ic.itemId(), ic.count().min(), ic.count().max());
        if (o.tableRef() != null && depth < 64) {
            Kit nested = catalog.get(o.tableRef());
            if (nested != null && nested.lines() != null) {
                Contents n = of(nested, catalog, memo, depth + 1);
                for (Map.Entry<String, Range> e : n.items.entrySet()) add(items, e.getKey(), e.getValue().min(), e.getValue().max());
                sources.addAll(n.sources);
            }
        }
        return new Contents(items, sources);
    }

    private static void add(Map<String, Range> items, String id, long min, long max) {
        Range r = items.get(id);
        items.put(id, r == null ? new Range(min, max) : new Range(r.min() + min, r.max() + max));
    }
}
