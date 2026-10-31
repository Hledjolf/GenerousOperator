package generousoperator.give;

import generousoperator.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code gifts.log}: one tab-separated line per gift, appended and never rewritten (Mod Settings Manager's
 * {@code changes.log} style). It answers "who spawned 400 gold bars", and it's also where {@link GiftHistory}
 * comes from at startup, so the limits survive a restart.
 *
 * <pre>
 * &lt;UTC time&gt; gift=&lt;id&gt; way=person|server giver=&lt;name&gt; giverId=&lt;id|server&gt; recipient=&lt;name&gt;
 *   recipientId=&lt;id&gt; kit=&lt;source/id&gt; source=&lt;caller&gt; delivery=full|partial|none limits=true|false
 *   once=true|false seed=&lt;n|-&gt; reason=&lt;json&gt; items=&lt;json&gt; commands=&lt;json&gt;
 * </pre>
 */
public final class GiftLog {
    /** One gift, as logged. {@code seed} is null for a command preset. */
    public record Line(Instant time, String giftId, boolean fromServer, String giver, String giverId, String recipient,
                       String recipientId, String kit, String source, String delivery, boolean limits, boolean once,
                       Long seed, String reason, Map<String, Long> items, List<String> commands) {
    }

    private final Path file;

    public GiftLog(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public static String format(Line l) {
        return l.time().truncatedTo(ChronoUnit.SECONDS)
                + "\tgift=" + oneLine(l.giftId())
                + "\tway=" + (l.fromServer() ? "server" : "person")
                + "\tgiver=" + oneLine(l.giver())
                + "\tgiverId=" + oneLine(l.giverId())
                + "\trecipient=" + oneLine(l.recipient())
                + "\trecipientId=" + oneLine(l.recipientId())
                + "\tkit=" + oneLine(l.kit())
                + "\tsource=" + oneLine(l.source())
                + "\tdelivery=" + l.delivery()
                + "\tlimits=" + l.limits()
                + "\tonce=" + l.once()
                + "\tseed=" + (l.seed() == null ? "-" : l.seed().toString())
                + "\treason=" + Json.write(l.reason())
                + "\titems=" + Json.write(l.items())
                + "\tcommands=" + Json.write(l.commands());
    }

    public synchronized void append(Line l) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Files.writeString(file, format(l) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Reads the log back, for {@link GiftHistory}. Lines it can't read are skipped and counted in
     * {@code skipped[0]}, so one damaged line never loses the rest.
     */
    public List<Line> read(int[] skipped) throws IOException {
        List<Line> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (raw.isBlank()) continue;
            Line l = parse(raw);
            if (l == null) skipped[0]++; else out.add(l);
        }
        return out;
    }

    static Line parse(String raw) {
        String[] parts = raw.split("\t");
        if (parts.length < 2) return null;
        Map<String, String> f = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) f.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
        }
        try {
            Instant time = Instant.parse(parts[0]);
            String kit = f.get("kit"), recipientId = f.get("recipientId"), delivery = f.get("delivery");
            if (kit == null || recipientId == null || delivery == null) return null;
            String seed = f.getOrDefault("seed", "-");
            return new Line(time, f.get("gift"), "server".equals(f.get("way")), f.get("giver"), f.get("giverId"),
                    f.get("recipient"), recipientId, kit, f.get("source"), delivery,
                    !"false".equals(f.get("limits")), "true".equals(f.get("once")),
                    "-".equals(seed) ? null : Long.valueOf(seed), json(f.get("reason")) instanceof String r ? r : null,
                    items(json(f.get("items"))), commands(json(f.get("commands"))));
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    /** A field's JSON value, or null if it's missing or damaged: the line still counts toward the limits. */
    private static Object json(String v) {
        if (v == null) return null;
        try {
            return Json.parse(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Long> items(Object v) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) if (e.getValue() instanceof Long n) out.put(String.valueOf(e.getKey()), n);
        }
        return out;
    }

    private static List<String> commands(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) for (Object c : l) if (c instanceof String s) out.add(s);
        return out;
    }

    private static String oneLine(String s) {
        return s == null ? "?" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
