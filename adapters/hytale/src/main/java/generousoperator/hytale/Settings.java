package generousoperator.hytale;

import generousoperator.catalog.Kit;
import generousoperator.give.CheckedGifts;
import generousoperator.give.GiftService;
import generousoperator.give.Rules;
import generousoperator.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generous Operator's settings, {@code config.json} in its data folder, edited through Mod Settings Manager or by
 * hand. Written with the defaults on first boot; a missing or wrong value falls back to its default, with a
 * warning. Server presents are one entry per calling mod, plain values only, so Mod Settings Manager can edit
 * them (its sidecar names the known callers' keys):
 *
 * <pre>
 * "TrustedMods": { "Hledjolf:PlaytestGamified": { "Trusted": false, "MaxPower": 1, "Unrated": false } }
 * </pre>
 */
final class Settings {
    /** Mods expected to ask for server presents; each has switches in the settings sidecar. */
    static final List<String> KNOWN_CALLERS = List.of("Hledjolf:PlaytestGamified");
    static final int DEFAULT_POWER = 1;

    private Rules rules = Rules.DEFAULT;

    Rules rules() {
        return rules;
    }

    /** Reads the file, writing it first if it doesn't exist. Returns problems for the log, or null. */
    String load(Path file) {
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, Json.pretty(toJson(Rules.DEFAULT)) + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "Can't write " + file + ": " + e.getMessage();
            }
            rules = Rules.DEFAULT;
            return null;
        }
        Map<String, Object> m;
        try {
            Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) return "Can't read " + file + ", using the defaults: it isn't a JSON object.";
            m = cast(parsed);
        } catch (IOException | RuntimeException e) {
            return "Can't read " + file + ", using the defaults: " + e.getMessage();
        }
        StringBuilder w = new StringBuilder();
        Rules d = Rules.DEFAULT;
        rules = new Rules(
                (int) integer(m, "GiverDailyCap", d.giverDailyCap(), 0, 100_000, w),
                (int) integer(m, "RecipientDailyCap", d.recipientDailyCap(), 0, 100_000, w),
                Duration.ofSeconds(integer(m, "PairCooldownSeconds", d.pairCooldown().getSeconds(), 0, 604_800, w)),
                (int) integer(m, "MaxDepth", d.maxDepth(), 1, 32, w),
                integer(m, "MaxItemsPerRoll", d.maxItemsPerRoll(), 1, 1_000_000, w),
                bool(m, "AnonymousGivers", d.anonymousGivers(), w),
                bool(m, "Announce", d.announce(), w),
                trusted(m.get("TrustedMods"), w));
        return w.length() == 0 ? null : w.toString().trim();
    }

    static Map<String, Object> toJson(Rules r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("GiverDailyCap", (long) r.giverDailyCap());
        m.put("RecipientDailyCap", (long) r.recipientDailyCap());
        m.put("PairCooldownSeconds", r.pairCooldown().getSeconds());
        m.put("AnonymousGivers", r.anonymousGivers());
        m.put("Announce", r.announce());
        m.put("MaxDepth", (long) r.maxDepth());
        m.put("MaxItemsPerRoll", r.maxItemsPerRoll());
        Map<String, Object> trusted = new LinkedHashMap<>();
        for (String mod : KNOWN_CALLERS) trusted.put(mod, entry(false, DEFAULT_POWER, false));
        for (Rules.Trust t : r.trusted()) trusted.put(t.caller(), entry(true, t.maxPower(), !t.unratedSources().isEmpty()));
        m.put("TrustedMods", trusted);
        return m;
    }

    private static Map<String, Object> entry(boolean trusted, int maxPower, boolean unrated) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("Trusted", trusted);
        o.put("MaxPower", (long) maxPower);
        o.put("Unrated", unrated);
        return o;
    }

    /**
     * One entry per calling mod, keyed by its id: {@code Trusted} switches it on, {@code MaxPower} caps the kit's
     * best roll, {@code Unrated} lets it give its own kits the power curve can't rate. A trusted mod may give its own
     * kits, Generous Operator's and the server's.
     */
    private static List<Rules.Trust> trusted(Object v, StringBuilder w) {
        List<Rules.Trust> out = new ArrayList<>();
        if (v == null) return out;
        if (!(v instanceof Map<?, ?> mods)) {
            w.append("TrustedMods should be an object of mod ids; no server presents are allowed. ");
            return out;
        }
        for (Map.Entry<?, ?> e : mods.entrySet()) {
            String mod = String.valueOf(e.getKey());
            String at = "TrustedMods." + mod;
            if (mod.indexOf(':') < 1 || mod.equals(CheckedGifts.UNIDENTIFIED)) {
                w.append(at).append(": not a mod id such as Hledjolf:PlaytestGamified; skipped. ");
                continue;
            }
            if (!(e.getValue() instanceof Map)) {
                w.append(at).append(" isn't an object; skipped. ");
                continue;
            }
            Map<String, Object> o = cast(e.getValue());
            StringBuilder ew = new StringBuilder();
            boolean on = bool(o, "Trusted", false, ew);
            long power = integer(o, "MaxPower", DEFAULT_POWER, 0, 4, ew);
            boolean unrated = bool(o, "Unrated", false, ew);
            if (ew.length() > 0) w.append(at).append(".").append(ew);
            if (!on) continue;
            out.add(new Rules.Trust(mod, Set.of(mod, GiftService.OWN_SOURCE, Kit.SERVER), (int) power, Set.of(),
                    unrated ? Set.of(mod) : Set.of()));
        }
        return out;
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def, StringBuilder w) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        w.append(key).append(" should be true or false; using ").append(def).append(". ");
        return def;
    }

    private static long integer(Map<String, Object> m, String key, long def, long min, long max, StringBuilder w) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Long l && l >= min && l <= max) return l;
        w.append(key).append(" should be a whole number from ").append(min).append(" to ").append(max).append("; using ").append(def).append(". ");
        return def;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }
}
