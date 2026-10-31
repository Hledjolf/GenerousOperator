package generousoperator.hytale;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import generousoperator.api.GiftResult;
import generousoperator.api.GiveCheck;
import generousoperator.api.GiveRequest;
import generousoperator.api.GiveStart;
import generousoperator.api.KitInfo;
import generousoperator.api.Person;
import generousoperator.api.Refusal;
import generousoperator.give.GiftLog;
import generousoperator.give.GiftService;
import generousoperator.give.Ports;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /generousoperator} (and {@code /gift}, when no other mod has it): give a kit to a player or to everyone
 * online, confirm a gift that asks first, list the kits, read the gift log, and reload. Works from the console
 * too. Every gift goes through the same service as the API, so the same checks, limits and log apply.
 */
final class GiftCommand extends AbstractAsyncCommand {
    static final String NAME = "generousoperator";
    static final String ALIAS = "gift";
    private static final long CONFIRM_MILLIS = 60_000;
    private static final int LOG_LINES = 10;

    private final GenerousOperatorPlugin plugin;
    private final String name;
    /** What waits for each person's {@code confirm}: one gift (with its token) or a gift to everyone. */
    private final Map<String, Waiting> waiting = new ConcurrentHashMap<>();

    private record Waiting(GiveRequest request, String token, List<String> everyone, long until) {
    }

    GiftCommand(GenerousOperatorPlugin plugin, String name) {
        super(name, "Generous Operator: give a kit to a player. /" + name + " help for more");
        this.plugin = plugin;
        this.name = name;
        setAllowsExtraArguments(true);
        // Each form checks its own permission, so the console and players without it get a clear answer.
        requireNoPermission();
    }

    @Override
    protected CompletableFuture<Void> executeAsync(CommandContext context) {
        Person giver = context.isPlayer() ? new Person(context.sender().getUuid().toString(), context.sender().getUsername())
                : HytalePlayers.console();
        List<String> out = new ArrayList<>();
        try {
            List<String> args = words(context.getInputString());
            if (args.isEmpty() && context.isPlayer()) {
                // The window opens even without the permission: it shows what's missing.
                PlayerRef p = HytalePlayers.player(giver.id());
                if (p == null || !GiftPage.open(p, plugin.windowFor(giver), plugin.getLogger())) out.add("Can't open the window: you aren't in a world.");
            } else {
                handle(giver, args, out);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().atSevere().withCause(e).log("[GenerousOperator] /%s failed", name);
            out.add("/" + name + " failed: " + e.getMessage());
        }
        for (String l : out) context.sendMessage(Message.raw(l));
        return CompletableFuture.completedFuture(null);
    }

    void handle(Person giver, List<String> args, List<String> out) {
        String first = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
        Ports.Players players = plugin.players();
        boolean mayGive = players.hasPermission(giver.id(), Ports.GIVE);
        switch (first) {
            case "", "help" -> usage(out);
            case "kits" -> {
                if (!mayGive) { noPermission(out); return; }
                kits(out);
            }
            case "log" -> {
                if (!mayGive) { noPermission(out); return; }
                if (args.size() > 2) out.add("Usage: /" + name + " log [player]"); else log(args.size() == 2 ? args.get(1) : null, out);
            }
            case "confirm" -> confirm(giver, out);
            case "reload" -> {
                if (!players.hasPermission(giver.id(), Ports.EDIT)) {
                    out.add("Reloading needs the " + Ports.EDIT + " permission.");
                    return;
                }
                out.add(plugin.reload());
            }
            default -> {
                if (args.size() != 2) { usage(out); return; }
                if (!mayGive) { noPermission(out); return; }
                give(giver, args.get(0), args.get(1), out);
            }
        }
    }

    private void usage(List<String> out) {
        out.add("/" + name + ": open the window");
        out.add("/" + name + " <player> <kit>: give a kit to a player");
        out.add("/" + name + " all <kit>: give it to everyone online (asks to confirm)");
        out.add("/" + name + " confirm: give what's waiting for your confirmation (within 60 seconds)");
        out.add("/" + name + " kits: the kits you can give");
        out.add("/" + name + " log [player]: the last gifts, or a player's");
        out.add("/" + name + " reload: read the catalogs and settings again");
    }

    private void noPermission(List<String> out) {
        out.add("Giving gifts needs the " + Ports.GIVE + " permission.");
    }

    private void give(Person giver, String who, String kit, List<String> out) {
        GiftService service = plugin.service();
        if (who.equalsIgnoreCase("all")) {
            List<String> everyone = new ArrayList<>();
            for (Person p : plugin.players().everyoneOnline()) everyone.add(p.id());
            if (everyone.isEmpty()) {
                out.add("Nobody is online.");
                return;
            }
            // Checked against the first player, so an unknown kit or a missing permission shows before confirming.
            GiveCheck c = service.check(GiveRequest.asPerson(kit, everyone.get(0), giver, "command", GiftService.OWN_SOURCE));
            if (!c.ok() && !perRecipient(c.refusal())) {
                out.add("Can't give that: " + c.refusal().message());
                return;
            }
            if (c.ok() && c.needsConfirmation()) {
                out.add("Can't give that to everyone: " + GiftService.CONFIRMS_EACH_MESSAGE);
                return;
            }
            waiting.put(giver.id(), new Waiting(GiveRequest.asPerson(kit, null, giver, "command", GiftService.OWN_SOURCE), null, everyone,
                    System.currentTimeMillis() + CONFIRM_MILLIS));
            String whom = everyone.size() == 1 ? "the one player online" : "all " + everyone.size() + " players online";
            out.add("Give " + kitName(c.kit(), kit) + " to " + whom + "? Type /" + name + " confirm within 60 seconds.");
            return;
        }
        PlayerRef p = HytalePlayers.byName(who);
        if (p == null) {
            out.add("No player called " + who + " is online.");
            return;
        }
        GiveRequest r = GiveRequest.asPerson(kit, p.getUuid().toString(), giver, "command", GiftService.OWN_SOURCE);
        GiveCheck c = service.check(r);
        if (!c.ok()) {
            out.add("Can't give that: " + c.refusal().message());
            return;
        }
        if (c.needsConfirmation()) {
            waiting.put(giver.id(), new Waiting(r, c.confirmToken(), null, System.currentTimeMillis() + CONFIRM_MILLIS));
            out.addAll(c.preview());
            out.add(c.confirmPrompt() + " Type /" + name + " confirm within 60 seconds.");
            return;
        }
        start(giver, r, null, out);
    }

    /** Refusals that depend on the recipient: a gift to everyone skips that player and goes on. */
    private static boolean perRecipient(Refusal r) {
        return switch (r.code()) {
            case "recipient_offline", "once_per_player", "recipient_daily_cap", "cooldown" -> true;
            default -> false;
        };
    }

    private void confirm(Person giver, List<String> out) {
        Waiting w = waiting.remove(giver.id());
        if (w == null || w.until() < System.currentTimeMillis()) {
            out.add("Nothing is waiting for your confirmation.");
            return;
        }
        if (w.everyone() == null) {
            start(giver, w.request(), w.token(), out);
            return;
        }
        String kit = w.request().kits().get(0);
        plugin.service().giveAll(giver, w.everyone(), w.request().kits(), w.request().reason(), w.request().source(), result -> {
            StringBuilder b = new StringBuilder("Gave ").append(kit).append(" to ").append(result.given().size())
                    .append(result.given().size() == 1 ? " player" : " players");
            if (!result.skipped().isEmpty()) {
                List<String> why = new ArrayList<>();
                for (Map.Entry<String, Refusal> e : result.skipped().entrySet()) {
                    Person p = plugin.players().online(e.getKey());
                    why.add((p == null ? e.getKey() : p.name()) + " (" + e.getValue().message() + ")");
                }
                b.append("; skipped ").append(String.join(", ", why));
            }
            plugin.players().tell(giver.id(), b.append('.').toString());
        });
        out.add("Giving " + kit + " to " + w.everyone().size() + (w.everyone().size() == 1 ? " player..." : " players..."));
    }

    private void start(Person giver, GiveRequest r, String token, List<String> out) {
        GiveStart s = plugin.service().give(r, token, result -> plugin.players().tell(giver.id(), finished(result)));
        if (!s.started()) out.add("Can't give that: " + s.refusal().message());
    }

    private String finished(GiftResult r) {
        Person p = plugin.players().online(r.recipientId());
        String who = p == null ? "the recipient" : p.name();
        String what = r.items().isEmpty() ? "" : ": " + items(r.items());
        return switch (r.delivery()) {
            case FULL -> "Gave " + r.kit() + " to " + who + what + ".";
            case PARTIAL -> "Gave part of " + r.kit() + " to " + who + what + ". " + (r.message() == null ? "" : r.message());
            case NONE -> "Couldn't give " + r.kit() + " to " + who + ". " + (r.message() == null ? "" : r.message());
        };
    }

    private String items(Map<String, Long> items) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Long> e : items.entrySet()) parts.add(e.getValue() + " x " + plugin.items().name(e.getKey()));
        return String.join(", ", parts);
    }

    private String kitName(String qualifiedId, String typed) {
        if (qualifiedId == null) return typed;
        for (KitInfo k : plugin.service().kits()) if (k.kit().equals(qualifiedId)) return k.name();
        return typed;
    }

    private void kits(List<String> out) {
        List<KitInfo> kits = plugin.service().kits();
        if (kits.isEmpty()) {
            out.add("There are no kits. Mods add them with a catalog; yours go in mods/Hledjolf_GenerousOperator/catalogs/.");
            return;
        }
        String group = null;
        List<KitInfo> sorted = new ArrayList<>(kits);
        sorted.sort((a, b) -> a.group().compareToIgnoreCase(b.group()));
        for (KitInfo k : sorted) {
            if (!k.group().equals(group)) {
                group = k.group();
                out.add(group + ":");
            }
            out.add("  " + k.kit() + " (" + k.name() + (k.form().equals("preset") ? ", commands" : "") + (k.oncePerPlayer() ? ", once per player" : "") + ")");
        }
    }

    private void log(String player, List<String> out) {
        List<GiftLog.Line> lines;
        int[] skipped = {0};
        try {
            lines = plugin.giftLog().read(skipped);
        } catch (IOException e) {
            out.add("Can't read the gift log: " + e.getMessage());
            return;
        }
        List<GiftLog.Line> shown = new ArrayList<>();
        for (GiftLog.Line l : lines) {
            if (player == null || player.equalsIgnoreCase(l.giver()) || player.equalsIgnoreCase(l.recipient())) shown.add(l);
        }
        if (shown.isEmpty()) {
            out.add(player == null ? "No gifts yet." : "No gifts to or from " + player + ".");
            return;
        }
        for (GiftLog.Line l : shown.subList(Math.max(0, shown.size() - LOG_LINES), shown.size())) {
            out.add(l.time().toString().replace('T', ' ').replace("Z", " UTC") + "  " + l.giver() + " -> " + l.recipient() + ": " + l.kit()
                    + (l.delivery().equals("full") ? "" : " (" + l.delivery() + ")") + (l.items().isEmpty() ? "" : ", " + items(l.items())));
        }
        if (skipped[0] > 0) out.add(skipped[0] + " damaged line" + (skipped[0] == 1 ? "" : "s") + " in the log were skipped.");
    }

    private List<String> words(String raw) {
        List<String> out = new ArrayList<>();
        for (String w : raw.trim().split("[ \t]+")) if (!w.isEmpty()) out.add(w);
        if (!out.isEmpty() && out.get(0).replace("/", "").equalsIgnoreCase(name)) out.remove(0);
        return out;
    }
}
