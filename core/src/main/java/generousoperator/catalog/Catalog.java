package generousoperator.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Every loaded catalog, merged and checked. Built once at startup and again on a reload.
 *
 * <p>The checks, none of which fails the whole load:
 * <ul>
 *   <li>an item id the live registry doesn't know is dropped from its kit, with a warning ({@code unknown_item});</li>
 *   <li>a table reference that doesn't resolve to a loaded item-list kit or table is dropped, with a warning
 *       ({@code unknown_table}, or {@code preset_ref} for a command preset, which can't be nested);</li>
 *   <li>a kit or table in a reference cycle is skipped, with the loop named ({@code cycle});</li>
 *   <li>a kit or table nested more than {@code maxDepth} deep is skipped ({@code too_deep}).</li>
 * </ul>
 */
public final class Catalog {
    private final Map<String, Kit> byQid;
    private final List<Issue> issues;

    private Catalog(Map<String, Kit> byQid, List<Issue> issues) {
        this.byQid = byQid;
        this.issues = issues;
    }

    public static Catalog empty() {
        return new Catalog(new LinkedHashMap<>(), new ArrayList<>());
    }

    /**
     * @param parsed every kit and table from every catalog, in load order (a repeated qualified id keeps the first)
     * @param knownItem the live item registry, including modded items
     * @param maxDepth the deepest nesting allowed (a table with no references has depth 1)
     * @param issues problems from parsing, to which this adds its own
     */
    public static Catalog build(List<Kit> parsed, Predicate<String> knownItem, int maxDepth, List<Issue> issues) {
        List<Issue> out = issues;
        Map<String, Kit> all = new LinkedHashMap<>();
        for (Kit k : parsed) {
            if (all.containsKey(k.qualifiedId())) {
                out.add(new Issue(Issue.Level.ERROR, k.qualifiedId(), "duplicate_id", "another catalog of " + k.source() + " already has this id; skipped"));
                continue;
            }
            all.put(k.qualifiedId(), k);
        }

        // Unknown items, and references that point at nothing or at a preset.
        Map<String, Kit> checked = new LinkedHashMap<>();
        for (Kit k : all.values()) checked.put(k.qualifiedId(), cleaned(k, all, knownItem, out));

        // Cycles: every kit on a loop is skipped.
        Set<String> removed = new HashSet<>();
        for (List<String> loop : cycles(checked)) {
            String path = String.join(" -> ", loop) + " -> " + loop.get(0);
            for (String q : loop) {
                if (removed.add(q)) out.add(new Issue(Issue.Level.ERROR, q, "cycle", "is part of a reference loop (" + path + "); skipped"));
            }
        }
        // Depth, measured without the loops.
        Map<String, Integer> depth = new HashMap<>();
        for (String q : checked.keySet()) {
            if (removed.contains(q)) continue;
            int d = depth(q, checked, removed, depth);
            if (d > maxDepth) {
                out.add(new Issue(Issue.Level.ERROR, q, "too_deep", "nests " + d + " tables deep, more than the limit of " + maxDepth + "; skipped"));
            }
        }
        for (Map.Entry<String, Integer> e : depth.entrySet()) if (e.getValue() > maxDepth) removed.add(e.getKey());

        // Whatever pointed at a skipped table loses that reference.
        Map<String, Kit> kept = new LinkedHashMap<>();
        for (Kit k : checked.values()) {
            if (removed.contains(k.qualifiedId())) continue;
            kept.put(k.qualifiedId(), withoutRefs(k, removed, out));
        }
        return new Catalog(kept, new ArrayList<>(out));
    }

    /**
     * This catalog plus kits that need no checks, such as CommandDeck presets no catalog entry describes (group
     * {@link Kit#OTHER_GROUP}). A kit whose qualified id is already taken is left out.
     */
    public Catalog plus(List<Kit> extra) {
        Map<String, Kit> m = new LinkedHashMap<>(byQid);
        for (Kit k : extra) m.putIfAbsent(k.qualifiedId(), k);
        return new Catalog(m, issues);
    }

    /** The kit or table with this qualified id ({@code <source>/<id>}), or null. */
    public Kit get(String qualifiedId) {
        return byQid.get(qualifiedId);
    }

    /**
     * Giftable kits matching what a person typed: a qualified id, or a bare id (every source that has it).
     * Tables that exist only to be nested aren't giftable and never match.
     */
    public List<Kit> find(String idOrQid) {
        List<Kit> out = new ArrayList<>();
        Kit exact = byQid.get(idOrQid);
        if (exact != null) {
            if (exact.giftable()) out.add(exact);
            return out;
        }
        for (Kit k : byQid.values()) if (k.giftable() && k.id().equals(idOrQid)) out.add(k);
        return out;
    }

    /** Every giftable kit, in load order. */
    public List<Kit> kits() {
        List<Kit> out = new ArrayList<>();
        for (Kit k : byQid.values()) if (k.giftable()) out.add(k);
        return out;
    }

    public List<Issue> issues() {
        return Collections.unmodifiableList(issues);
    }

    // ---------------------------------------------------------------------------------------------------

    private static Kit cleaned(Kit k, Map<String, Kit> all, Predicate<String> knownItem, List<Issue> out) {
        if (k.lines() == null) return k;
        List<Table.Line> lines = new ArrayList<>();
        for (Table.Line line : k.lines()) {
            if (line instanceof Table.ChanceLine) {
                Table.ChanceLine c = (Table.ChanceLine) line;
                lines.add(new Table.ChanceLine(c.percent(), cleaned(c.outcome(), k, all, knownItem, out)));
            } else {
                Table.PickLine p = (Table.PickLine) line;
                List<Table.Choice> from = new ArrayList<>();
                for (Table.Choice ch : p.from()) from.add(new Table.Choice(ch.weight(), cleaned(ch.outcome(), k, all, knownItem, out)));
                lines.add(new Table.PickLine(p.count(), p.repeat(), from));
            }
        }
        return withLines(k, lines);
    }

    private static Table.Outcome cleaned(Table.Outcome o, Kit k, Map<String, Kit> all, Predicate<String> knownItem, List<Issue> out) {
        List<Table.ItemCount> items = new ArrayList<>();
        for (Table.ItemCount ic : o.items()) {
            if (knownItem.test(ic.itemId())) items.add(ic);
            else out.add(new Issue(Issue.Level.WARNING, k.qualifiedId(), "unknown_item", "item \"" + ic.itemId() + "\" isn't in the game's item registry; left out"));
        }
        String ref = o.tableRef();
        if (ref != null) {
            Kit target = all.get(ref);
            if (target == null) {
                out.add(new Issue(Issue.Level.WARNING, k.qualifiedId(), "unknown_table", "table \"" + ref + "\" isn't loaded; that roll is left out"));
                ref = null;
            } else if (target.isPreset()) {
                out.add(new Issue(Issue.Level.WARNING, k.qualifiedId(), "preset_ref", "\"" + ref + "\" is a command preset, which can't be nested in a table; left out"));
                ref = null;
            }
        }
        return new Table.Outcome(items, ref);
    }

    private static Kit withoutRefs(Kit k, Set<String> removed, List<Issue> out) {
        if (k.lines() == null) return k;
        boolean changed = false;
        List<Table.Line> lines = new ArrayList<>();
        for (Table.Line line : k.lines()) {
            if (line instanceof Table.ChanceLine) {
                Table.ChanceLine c = (Table.ChanceLine) line;
                Table.Outcome o = withoutRef(c.outcome(), k, removed, out);
                changed |= o != c.outcome();
                lines.add(new Table.ChanceLine(c.percent(), o));
            } else {
                Table.PickLine p = (Table.PickLine) line;
                List<Table.Choice> from = new ArrayList<>();
                for (Table.Choice ch : p.from()) {
                    Table.Outcome o = withoutRef(ch.outcome(), k, removed, out);
                    changed |= o != ch.outcome();
                    from.add(new Table.Choice(ch.weight(), o));
                }
                lines.add(new Table.PickLine(p.count(), p.repeat(), from));
            }
        }
        return changed ? withLines(k, lines) : k;
    }

    private static Table.Outcome withoutRef(Table.Outcome o, Kit k, Set<String> removed, List<Issue> out) {
        if (o.tableRef() == null || !removed.contains(o.tableRef())) return o;
        out.add(new Issue(Issue.Level.WARNING, k.qualifiedId(), "unknown_table", "table \"" + o.tableRef() + "\" was skipped; that roll is left out"));
        return new Table.Outcome(o.items(), null);
    }

    private static Kit withLines(Kit k, List<Table.Line> lines) {
        return new Kit(k.source(), k.id(), k.name(), k.description(), k.giftable(), k.preset(), lines, k.group(), k.kind(),
                k.tier(), k.occasion(), k.biome(), k.tags(), k.limits(), k.oncePerPlayer(), k.message(), k.assignedMod());
    }

    /** Qualified ids of the tables a kit refers to directly. */
    static List<String> refs(Kit k) {
        List<String> out = new ArrayList<>();
        if (k.lines() == null) return out;
        for (Table.Line line : k.lines()) {
            if (line instanceof Table.ChanceLine) {
                String r = ((Table.ChanceLine) line).outcome().tableRef();
                if (r != null) out.add(r);
            } else {
                for (Table.Choice ch : ((Table.PickLine) line).from()) if (ch.outcome().tableRef() != null) out.add(ch.outcome().tableRef());
            }
        }
        return out;
    }

    /** Each strongly connected group of two or more kits, or one kit that refers to itself (Tarjan). */
    private static List<List<String>> cycles(Map<String, Kit> kits) {
        Tarjan t = new Tarjan(kits);
        for (String q : kits.keySet()) if (!t.index.containsKey(q)) t.visit(q);
        return t.loops;
    }

    private static final class Tarjan {
        final Map<String, Kit> kits;
        final Map<String, Integer> index = new HashMap<>();
        final Map<String, Integer> low = new HashMap<>();
        final List<String> stack = new ArrayList<>();
        final Set<String> onStack = new HashSet<>();
        final List<List<String>> loops = new ArrayList<>();
        int next;

        Tarjan(Map<String, Kit> kits) {
            this.kits = kits;
        }

        void visit(String q) {
            index.put(q, next);
            low.put(q, next);
            next++;
            stack.add(q);
            onStack.add(q);
            List<String> rs = refs(kits.get(q));
            for (String r : rs) {
                if (!kits.containsKey(r)) continue;
                if (!index.containsKey(r)) {
                    visit(r);
                    low.put(q, Math.min(low.get(q), low.get(r)));
                } else if (onStack.contains(r)) {
                    low.put(q, Math.min(low.get(q), index.get(r)));
                }
            }
            if (low.get(q).equals(index.get(q))) {
                List<String> group = new ArrayList<>();
                String w;
                do {
                    w = stack.remove(stack.size() - 1);
                    onStack.remove(w);
                    group.add(w);
                } while (!w.equals(q));
                Collections.reverse(group);
                if (group.size() > 1 || rs.contains(q)) loops.add(group);
            }
        }
    }

    private static int depth(String q, Map<String, Kit> kits, Set<String> removed, Map<String, Integer> memo) {
        Integer known = memo.get(q);
        if (known != null) return known;
        int d = 1;
        for (String r : refs(kits.get(q))) {
            if (removed.contains(r) || !kits.containsKey(r)) continue;
            d = Math.max(d, 1 + depth(r, kits, removed, memo));
        }
        memo.put(q, d);
        return d;
    }
}
