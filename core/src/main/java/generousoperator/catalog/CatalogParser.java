package generousoperator.catalog;

import generousoperator.json.Json;
import generousoperator.json.JsonException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads one catalog sidecar ({@code generousoperator/catalog.json} in a mod, or a file in the server's
 * {@code catalogs/} folder). The format is documented in {@code docs/catalog/README.md}.
 *
 * <p>Nothing here fails the whole file except unreadable JSON or a wrong {@code format}: a bad entry is
 * skipped with an {@link Issue.Level#ERROR}, a bad reference or unknown field is dropped with a warning.
 * Whether item ids and table references resolve is checked later, by {@link Catalog}.
 */
public final class CatalogParser {
    public static final String FORMAT = "generousoperator.catalog";
    public static final int FORMAT_MAJOR = 1;
    public static final int FORMAT_MINOR = 0;

    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Set<String> TOP = Set.of("format", "format_version", "mod", "kits", "tables");
    private static final Set<String> MOD = Set.of("id", "name", "version");
    private static final Set<String> ENTRY = Set.of("id", "name", "description", "preset", "items", "rolls", "group",
            "kind", "tier", "occasion", "biome", "tags", "limits", "oncePerPlayer", "message");
    private static final Set<String> TABLE = Set.of("id", "name", "description", "items", "rolls", "tags");
    private static final Set<String> CHANCE = Set.of("chance", "items", "table", "note");
    private static final Set<String> PICK = Set.of("pick", "repeat", "from", "note");
    private static final Set<String> CHOICE = Set.of("weight", "items", "table", "note");
    private static final Set<String> ITEM = Set.of("id", "count");

    private final String source;
    private final String where;
    private final boolean serverCatalog;
    private final List<Issue> issues;
    private boolean newerMinor;

    private CatalogParser(String source, String where, boolean serverCatalog, List<Issue> issues) {
        this.source = source;
        this.where = where;
        this.serverCatalog = serverCatalog;
        this.issues = issues;
    }

    /**
     * Parses a catalog a mod ships. {@code modId} is the mod's id as the game knows it (the loader's, not the
     * file's), and becomes every kit's {@code source}.
     */
    public static List<Kit> parseMod(String text, String modId, List<Issue> issues) {
        return new CatalogParser(modId, modId, false, issues).parse(text);
    }

    /**
     * Parses one of the server's own catalogs. Every kit's source is {@link Kit#SERVER}; the file's
     * {@code mod.id}, if any, is kept only as the kit's assigned mod (a label).
     */
    public static List<Kit> parseServer(String text, String fileName, List<Issue> issues) {
        return new CatalogParser(Kit.SERVER, Kit.SERVER + ":" + fileName, true, issues).parse(text);
    }

    private List<Kit> parse(String text) {
        List<Kit> out = new ArrayList<>();
        Object root;
        try {
            root = Json.parse(text);
        } catch (JsonException e) {
            error(where, "bad_json", e.getMessage());
            return out;
        }
        if (!(root instanceof Map)) {
            error(where, "bad_format", "a catalog is a JSON object");
            return out;
        }
        Map<String, Object> top = map(root);
        if (!FORMAT.equals(top.get("format"))) {
            error(where, "bad_format", "\"format\" must be \"" + FORMAT + "\"");
            return out;
        }
        Object version = top.get("format_version");
        if (!(version instanceof String) || !((String) version).matches("\\d+\\.\\d+")) {
            error(where, "bad_format", "\"format_version\" must be like \"1.0\"");
            return out;
        }
        String[] mm = ((String) version).split("\\.");
        if (Integer.parseInt(mm[0]) != FORMAT_MAJOR) {
            error(where, "needs_newer", "format " + version + " needs a newer Generous Operator (this one reads " + FORMAT_MAJOR + ".x)");
            return out;
        }
        newerMinor = Integer.parseInt(mm[1]) > FORMAT_MINOR;
        if (newerMinor) warn(where, "newer_minor", "format " + version + " is newer than " + FORMAT_MAJOR + "." + FORMAT_MINOR + "; fields this version doesn't know are ignored");
        unknownKeys(top, TOP, where);

        String assigned = null;
        Object mod = top.get("mod");
        if (mod instanceof Map) {
            Map<String, Object> m = map(mod);
            unknownKeys(m, MOD, where + " mod");
            Object id = m.get("id");
            if (id instanceof String) {
                if (serverCatalog) assigned = (String) id;
                else if (!id.equals(source)) warn(where + " mod.id", "mod_mismatch", "says \"" + id + "\", but the catalog ships in " + source + "; using " + source);
            }
        } else if (!serverCatalog) {
            warn(where, "no_mod", "no \"mod\" object; using " + source);
        }

        Set<String> seen = new HashSet<>();
        entries(top.get("kits"), "kits", true, assigned, seen, out);
        entries(top.get("tables"), "tables", false, assigned, seen, out);
        return out;
    }

    private void entries(Object list, String key, boolean giftable, String assigned, Set<String> seen, List<Kit> out) {
        if (list == null) return;
        if (!(list instanceof List)) {
            error(where + " " + key, "bad_format", "\"" + key + "\" must be a list");
            return;
        }
        List<?> l = (List<?>) list;
        for (int k = 0; k < l.size(); k++) {
            String at = where + " " + key + "[" + k + "]";
            if (!(l.get(k) instanceof Map)) {
                error(at, "bad_entry", "an entry is a JSON object");
                continue;
            }
            try {
                Kit kit = entry(map(l.get(k)), at, giftable, assigned);
                if (!seen.add(kit.id())) {
                    error(at, "duplicate_id", "id \"" + kit.id() + "\" is already used in this catalog; this entry is skipped");
                    continue;
                }
                out.add(kit);
            } catch (Bad b) {
                error(b.at, b.code, b.getMessage() + "; this entry is skipped");
            }
        }
    }

    private Kit entry(Map<String, Object> e, String at, boolean giftable, String assigned) {
        unknownKeys(e, giftable ? ENTRY : TABLE, at);
        String id = string(e, "id", at, true);
        if (!ID.matcher(id).matches()) throw new Bad(at + ".id", "bad_id", "id \"" + id + "\" must be 1 to 64 lowercase letters, digits, '_' or '-', starting with a letter or digit");
        String name = string(e, "name", at, giftable);
        if (name == null) name = id;
        String preset = giftable ? string(e, "preset", at, false) : null;
        boolean hasItems = e.containsKey("items"), hasRolls = e.containsKey("rolls");
        int forms = (preset != null ? 1 : 0) + (hasItems ? 1 : 0) + (hasRolls ? 1 : 0);
        if (forms != 1) {
            String what = giftable ? "\"preset\", \"items\" or \"rolls\"" : "\"items\" or \"rolls\"";
            throw new Bad(at, "bad_form", "needs exactly one of " + what);
        }
        List<Table.Line> lines = null;
        if (hasItems) {
            lines = List.of(new Table.ChanceLine(100, new Table.Outcome(items(e.get("items"), at + ".items"), null)));
        } else if (hasRolls) {
            lines = rolls(e.get("rolls"), at + ".rolls");
        }
        Integer tier = null;
        if (e.get("tier") != null) {
            if (!(e.get("tier") instanceof Long)) throw new Bad(at + ".tier", "bad_field", "\"tier\" must be a whole number");
            tier = (int) (long) (Long) e.get("tier");
        }
        List<String> tags = new ArrayList<>();
        Object t = e.get("tags");
        if (t instanceof List) {
            for (Object o : (List<?>) t) if (o instanceof String) tags.add((String) o); else warn(at + ".tags", "bad_field", "a tag must be a string; skipped");
        } else if (t != null) warn(at + ".tags", "bad_field", "\"tags\" must be a list of strings; ignored");
        String group = giftable ? string(e, "group", at, false) : null;
        return new Kit(source, id, name, string(e, "description", at, false), giftable, preset, lines,
                giftable ? (group == null ? Kit.OTHER_GROUP : group) : null,
                giftable ? string(e, "kind", at, false) : null, tier,
                giftable ? string(e, "occasion", at, false) : null,
                giftable ? string(e, "biome", at, false) : null,
                tags, bool(e, "limits", at, true), bool(e, "oncePerPlayer", at, false),
                giftable ? string(e, "message", at, false) : null, assigned);
    }

    private List<Table.Line> rolls(Object v, String at) {
        if (!(v instanceof List)) throw new Bad(at, "bad_field", "\"rolls\" must be a list");
        List<Table.Line> out = new ArrayList<>();
        List<?> l = (List<?>) v;
        for (int k = 0; k < l.size(); k++) {
            String la = at + "[" + k + "]";
            if (!(l.get(k) instanceof Map)) throw new Bad(la, "bad_field", "a roll line is a JSON object");
            Map<String, Object> m = map(l.get(k));
            if (m.containsKey("pick")) {
                unknownKeys(m, PICK, la);
                Dice count = dice(m.get("pick"), la + ".pick");
                Object from = m.get("from");
                if (!(from instanceof List) || ((List<?>) from).isEmpty()) throw new Bad(la + ".from", "bad_field", "a pick line needs a non-empty \"from\" list");
                List<Table.Choice> choices = new ArrayList<>();
                List<?> fl = (List<?>) from;
                for (int c = 0; c < fl.size(); c++) {
                    String ca = la + ".from[" + c + "]";
                    if (!(fl.get(c) instanceof Map)) throw new Bad(ca, "bad_field", "a choice is a JSON object");
                    Map<String, Object> cm = map(fl.get(c));
                    unknownKeys(cm, CHOICE, ca);
                    long weight = 1;
                    if (cm.get("weight") != null) {
                        if (!(cm.get("weight") instanceof Long) || (Long) cm.get("weight") < 1) throw new Bad(ca + ".weight", "bad_field", "\"weight\" must be a whole number of at least 1");
                        weight = (Long) cm.get("weight");
                    }
                    choices.add(new Table.Choice(weight, outcome(cm, ca)));
                }
                out.add(new Table.PickLine(count, bool(m, "repeat", la, false), choices));
            } else {
                unknownKeys(m, CHANCE, la);
                double chance = 100;
                if (m.get("chance") != null) {
                    if (!(m.get("chance") instanceof Number)) throw new Bad(la + ".chance", "bad_field", "\"chance\" is a percentage, 0 to 100");
                    chance = ((Number) m.get("chance")).doubleValue();
                    if (chance < 0 || chance > 100) throw new Bad(la + ".chance", "bad_field", "\"chance\" is a percentage, 0 to 100");
                }
                out.add(new Table.ChanceLine(chance, outcome(m, la)));
            }
        }
        return out;
    }

    private Table.Outcome outcome(Map<String, Object> m, String at) {
        List<Table.ItemCount> items = m.containsKey("items") ? items(m.get("items"), at + ".items") : List.of();
        String ref = null;
        if (m.get("table") != null) {
            if (!(m.get("table") instanceof String)) throw new Bad(at + ".table", "bad_field", "\"table\" must be a table id");
            ref = qualifyRef((String) m.get("table"));
            if (ref == null) {
                warn(at + ".table", "bad_ref", "\"" + m.get("table") + "\" isn't a table id or <Group:Name>/<id>; ignored");
            }
        }
        if (items.isEmpty() && ref == null && !m.containsKey("items")) throw new Bad(at, "bad_field", "needs \"items\" or \"table\"");
        return new Table.Outcome(items, ref);
    }

    private List<Table.ItemCount> items(Object v, String at) {
        if (!(v instanceof List)) throw new Bad(at, "bad_field", "\"items\" must be a list");
        List<Table.ItemCount> out = new ArrayList<>();
        List<?> l = (List<?>) v;
        for (int k = 0; k < l.size(); k++) {
            String ia = at + "[" + k + "]";
            if (!(l.get(k) instanceof Map)) throw new Bad(ia, "bad_field", "an item is {\"id\": ..., \"count\": ...}");
            Map<String, Object> m = map(l.get(k));
            unknownKeys(m, ITEM, ia);
            Object id = m.get("id");
            if (!(id instanceof String) || ((String) id).isBlank()) throw new Bad(ia + ".id", "bad_field", "an item needs an \"id\"");
            Dice count = m.containsKey("count") ? dice(m.get("count"), ia + ".count") : Dice.constant(1);
            out.add(new Table.ItemCount((String) id, count));
        }
        return out;
    }

    /** A bare id means a table in this same catalog. Returns null if the reference isn't well formed. */
    private String qualifyRef(String ref) {
        int slash = ref.lastIndexOf('/');
        if (slash < 0) return ID.matcher(ref).matches() ? Kit.qualify(source, ref) : null;
        String src = ref.substring(0, slash), id = ref.substring(slash + 1);
        if (src.isEmpty() || !ID.matcher(id).matches()) return null;
        return ref;
    }

    private Dice dice(Object v, String at) {
        try {
            return Dice.of(v);
        } catch (IllegalArgumentException e) {
            throw new Bad(at, "bad_count", e.getMessage());
        }
    }

    private String string(Map<String, Object> m, String key, String at, boolean required) {
        Object v = m.get(key);
        if (v == null) {
            if (required) throw new Bad(at, "bad_field", "needs \"" + key + "\"");
            return null;
        }
        if (!(v instanceof String)) throw new Bad(at + "." + key, "bad_field", "\"" + key + "\" must be a string");
        return (String) v;
    }

    private boolean bool(Map<String, Object> m, String key, String at, boolean dflt) {
        Object v = m.get(key);
        if (v == null) return dflt;
        if (!(v instanceof Boolean)) throw new Bad(at + "." + key, "bad_field", "\"" + key + "\" must be true or false");
        return (Boolean) v;
    }

    private void unknownKeys(Map<String, Object> m, Set<String> known, String at) {
        for (String k : m.keySet()) {
            if (!known.contains(k) && !newerMinor) warn(at, "unknown_field", "unknown field \"" + k + "\"; ignored");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private void warn(String at, String code, String message) {
        issues.add(new Issue(Issue.Level.WARNING, at, code, message));
    }

    private void error(String at, String code, String message) {
        issues.add(new Issue(Issue.Level.ERROR, at, code, message));
    }

    private static final class Bad extends RuntimeException {
        final String at;
        final String code;

        Bad(String at, String code, String message) {
            super(message);
            this.at = at;
            this.code = code;
        }
    }
}
