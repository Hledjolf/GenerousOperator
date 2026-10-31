package generousoperator.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Rolls an item-list kit. The same kit and seed always give the same items, so a disputed roll can be
 * replayed from the log line.
 */
public final class Roller {
    private Roller() {
    }

    /**
     * @param items what came out, by item id, in the order first rolled
     * @param capped true if the roll hit {@code maxItems} and stopped adding
     * @param issues references that stopped resolving since load (skipped, as at load)
     */
    public record Roll(long seed, Map<String, Long> items, boolean capped, List<Issue> issues) {
        public long total() {
            long t = 0;
            for (long n : items.values()) t += n;
            return t;
        }
    }

    /**
     * @param catalog to resolve nested tables (the one the kit came from)
     * @param maxItems the most items one roll may give, counted one by one (a stack of 64 is 64)
     */
    public static Roll roll(Kit kit, Catalog catalog, long seed, long maxItems) {
        if (kit.lines() == null) throw new IllegalArgumentException(kit.qualifiedId() + " is a command preset, not an item list");
        State st = new State(new Random(seed), catalog, maxItems);
        st.lines(kit, 1);
        return new Roll(seed, st.items, st.capped, st.issues);
    }

    private static final class State {
        final Random rng;
        final Catalog catalog;
        final long maxItems;
        final Map<String, Long> items = new LinkedHashMap<>();
        final List<Issue> issues = new ArrayList<>();
        long total;
        boolean capped;

        State(Random rng, Catalog catalog, long maxItems) {
            this.rng = rng;
            this.catalog = catalog;
            this.maxItems = maxItems;
        }

        void lines(Kit kit, int depth) {
            for (Table.Line line : kit.lines()) {
                if (line instanceof Table.ChanceLine) {
                    Table.ChanceLine c = (Table.ChanceLine) line;
                    // Drawn even at 100%, so a seed replays the same way whatever the chances are.
                    double r = rng.nextDouble() * 100;
                    if (r < c.percent()) outcome(kit, c.outcome(), depth);
                } else {
                    pick(kit, (Table.PickLine) line, depth);
                }
            }
        }

        void pick(Kit kit, Table.PickLine p, int depth) {
            long n = p.count().roll(rng);
            List<Table.Choice> pool = new ArrayList<>(p.from());
            for (long k = 0; k < n && !pool.isEmpty(); k++) {
                long sum = 0;
                for (Table.Choice c : pool) sum += c.weight();
                long r = (long) (rng.nextDouble() * sum);
                int chosen = pool.size() - 1;
                for (int i = 0; i < pool.size(); i++) {
                    r -= pool.get(i).weight();
                    if (r < 0) { chosen = i; break; }
                }
                Table.Choice c = p.repeat() ? pool.get(chosen) : pool.remove(chosen);
                outcome(kit, c.outcome(), depth);
            }
        }

        void outcome(Kit kit, Table.Outcome o, int depth) {
            for (Table.ItemCount ic : o.items()) add(ic.itemId(), ic.count().roll(rng));
            if (o.tableRef() == null) return;
            Kit nested = catalog.get(o.tableRef());
            if (nested == null || nested.lines() == null) {
                issues.add(new Issue(Issue.Level.WARNING, kit.qualifiedId(), "unknown_table", "table \"" + o.tableRef() + "\" is no longer loaded; that roll was left out"));
                return;
            }
            // Depth is checked at load; this only guards against a catalog that changed under a running roll.
            if (depth >= 64) return;
            lines(nested, depth + 1);
        }

        void add(String id, long n) {
            if (n <= 0) return;
            if (total + n > maxItems) {
                n = maxItems - total;
                capped = true;
                if (n <= 0) return;
            }
            total += n;
            items.merge(id, n, Long::sum);
        }
    }
}
