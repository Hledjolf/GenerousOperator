package generousoperator.give;

import generousoperator.api.GiftResult;
import generousoperator.api.Gifts;
import generousoperator.api.GiveCheck;
import generousoperator.api.GiveListener;
import generousoperator.api.GiveRequest;
import generousoperator.api.GiveStart;
import generousoperator.api.KitInfo;
import generousoperator.api.Person;
import generousoperator.api.Refusal;
import generousoperator.catalog.Catalog;
import generousoperator.catalog.Contents;
import generousoperator.catalog.Kit;
import generousoperator.catalog.Roller;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Every gift goes through here: the window, the command, and other mods through {@link Gifts}. It resolves the
 * kit (and seasonal variants), applies the rules in a fixed order, rolls item lists, hands the gift to
 * CommandDeck (as a person) or the inventory (from the server), then logs it, tells the recipient and,
 * if the operator wants, everyone.
 *
 * <p>The checks, in order (the first that fails is reported): the request's shape; the kit; the recipient
 * online; who may give (the {@code generousoperator.give} permission as a person, or a trust rule from the
 * server, with the power cap); once per player; the daily caps and the cooldown; then CommandDeck's own check
 * for a gift given as a person.
 */
public final class GiftService implements Gifts {
    /** The source for gifts from Generous Operator's own window and command. */
    public static final String OWN_SOURCE = "Hledjolf:GenerousOperator";
    /** The refusal for a batch of a kit that CommandDeck confirms one recipient at a time. */
    public static final String CONFIRMS_EACH = "confirms_each";
    public static final String CONFIRMS_EACH_MESSAGE = "This kit asks CommandDeck to confirm each recipient, so give it to one player at a time.";

    private static final Duration TOKEN_LIFE = Duration.ofSeconds(60);
    private static final int MAX_SOURCE = 120;

    private final Ports.Players players;
    private final Ports.Runner runner;
    private final Ports.Inventory inventory;
    private final Ports.Items items;
    private final Clock clock;
    private final GiftLog log;
    private final GiftHistory history;
    private final Consumer<String> serverLog;
    private final SecureRandom random = new SecureRandom();

    private volatile Catalog catalog = Catalog.empty();
    private volatile Rules rules = Rules.DEFAULT;
    private volatile Ports.Occasions occasions = Ports.Occasions.NONE;
    private volatile Ports.PowerCurve power = Ports.PowerCurve.NONE;
    private final Map<String, Pending> tokens = new HashMap<>();

    private record Pending(GiveRequest request, String kit, Instant expires) {
    }

    /** What the checks decided: the kit, or why not. */
    private record Decision(Kit kit, Person recipient, Refusal refusal, Ports.Runner.Check runnerCheck, List<String> preview) {
    }

    /**
     * @param serverLog where problems go that nobody is waiting for (a log file that can't be written)
     */
    public GiftService(Ports.Players players, Ports.Runner runner, Ports.Inventory inventory, Ports.Items items,
                       Clock clock, GiftLog log, GiftHistory history, Consumer<String> serverLog) {
        this.players = players;
        this.runner = runner;
        this.inventory = inventory;
        this.items = items;
        this.clock = clock;
        this.log = log;
        this.history = history;
        this.serverLog = serverLog;
    }

    // ----- configuration ------------------------------------------------------------------------------

    /** Replaces the catalog (startup and reload), adding every {@code Gifts} preset no catalog entry describes. */
    public void catalog(Catalog c) {
        List<String> described = new ArrayList<>();
        for (Kit k : c.kits()) if (k.isPreset()) described.add(k.preset());
        List<Kit> other = new ArrayList<>();
        if (runner.available()) {
            for (Ports.Runner.Preset p : runner.giftPresets()) {
                if (described.contains(p.id())) continue;
                String source = p.mod() == null ? Kit.SERVER : p.mod();
                other.add(new Kit(source, p.id(), p.name() == null ? p.id() : p.name(), p.description(), true, p.id(), null,
                        Kit.OTHER_GROUP, null, null, null, null, List.of(), true, false, null, null));
            }
        }
        catalog = c.plus(other);
    }

    public Catalog catalog() {
        return catalog;
    }

    public void rules(Rules r) {
        rules = r;
    }

    /** The clock the limits and the log go by. */
    public Clock clock() {
        return clock;
    }

    public Rules rules() {
        return rules;
    }

    public void occasions(Ports.Occasions o) {
        occasions = o == null ? Ports.Occasions.NONE : o;
    }

    /** Which occasions are on today (never null). */
    public Ports.Occasions occasions() {
        return occasions;
    }

    public void power(Ports.PowerCurve p) {
        power = p == null ? Ports.PowerCurve.NONE : p;
    }

    // ----- the API ------------------------------------------------------------------------------------

    @Override
    public String apiVersion() {
        return API_VERSION;
    }

    @Override
    public List<KitInfo> kits() {
        List<KitInfo> out = new ArrayList<>();
        for (Kit k : catalog.kits()) out.add(info(k));
        return out;
    }

    public static KitInfo info(Kit k) {
        return new KitInfo(k.qualifiedId(), k.name(), k.description(), k.isPreset() ? "preset" : "items", k.source(), k.group(),
                k.kind(), k.tier(), k.occasion(), k.biome(), k.tags(), k.oncePerPlayer(), k.limits());
    }

    @Override
    public GiveCheck check(GiveRequest request) {
        Decision d = decide(request, false);
        if (d.refusal() != null) return new GiveCheck(false, d.refusal(), d.kit() == null ? null : d.kit().qualifiedId(), List.of(), false, null, null);
        boolean confirm = d.runnerCheck() != null && d.runnerCheck().needsConfirmation();
        String token = null, prompt = null;
        if (confirm) {
            token = newId(16);
            prompt = d.runnerCheck().confirmPrompt();
            synchronized (tokens) {
                expireTokens();
                tokens.put(token, new Pending(request, d.kit().qualifiedId(), clock.instant().plus(TOKEN_LIFE)));
            }
        }
        return new GiveCheck(true, null, d.kit().qualifiedId(), d.preview(), confirm, prompt, token);
    }

    @Override
    public GiveStart give(GiveRequest request, String confirmToken, GiveListener listener) {
        return start(request, confirmToken, false, listener);
    }

    /**
     * Gives the same kit to several players, one after another (CommandDeck runs one gift per giver at a time).
     * The giver confirmed once, for the whole batch. A recipient who is refused is skipped, and the batch goes on.
     *
     * @param done hears once, with every result and every refusal, by recipient id
     */
    public void giveAll(Person giver, List<String> recipientIds, List<String> kits, String reason, String source,
                        Consumer<BatchResult> done) {
        giveAll(giver, recipientIds, kits, reason, source, (id, outcome) -> {}, () -> false, done);
    }

    /**
     * {@link #giveAll(Person, List, List, String, String, Consumer)}, telling {@code each} as every recipient is
     * given or skipped (a {@link GiftResult} or a {@link Refusal}), and stopping before the next recipient once
     * {@code stop} says so. Those not reached are in {@link BatchResult#notReached()}.
     */
    public void giveAll(Person giver, List<String> recipientIds, List<String> kits, String reason, String source,
                        BiConsumer<String, Object> each, BooleanSupplier stop, Consumer<BatchResult> done) {
        List<String> queue = new ArrayList<>(recipientIds);
        BatchResult result = new BatchResult(new LinkedHashMap<>(), new LinkedHashMap<>(), new ArrayList<>());
        next(giver, queue, kits, reason, source, result, each, stop, done);
    }

    /** A batch's outcome: what each recipient got, why the others were skipped, and who a Stop left out. */
    public record BatchResult(Map<String, GiftResult> given, Map<String, Refusal> skipped, List<String> notReached) {
    }

    private void next(Person giver, List<String> queue, List<String> kits, String reason, String source, BatchResult result,
                      BiConsumer<String, Object> each, BooleanSupplier stop, Consumer<BatchResult> done) {
        while (!queue.isEmpty()) {
            if (stop.getAsBoolean()) {
                result.notReached().addAll(queue);
                queue.clear();
                break;
            }
            String recipient = queue.remove(0);
            GiveStart s = start(new GiveRequest(kits, recipient, giver, reason, source), null, true, r -> {
                result.given().put(recipient, r);
                each.accept(recipient, r);
                next(giver, queue, kits, reason, source, result, each, stop, done);
            });
            if (s.started()) return;   // the listener carries on with the rest
            result.skipped().put(recipient, s.refusal());
            each.accept(recipient, s.refusal());
        }
        done.accept(result);
    }

    /**
     * A kit's power on its best roll: its strongest item's zone. Null for a command preset, an unknown kit, or a
     * kit with any item the power curve doesn't rate.
     */
    public Integer power(String kit) {
        Kit k = catalog.get(kit);
        if (k == null || k.isPreset()) return null;
        return power(Contents.of(k, catalog));
    }

    private Integer power(Contents contents) {
        Integer kitPower = 0;
        for (Map.Entry<String, Contents.Range> e : contents.items().entrySet()) {
            Integer p = power.power(e.getKey(), e.getValue().max());
            if (p == null) return null;
            kitPower = Math.max(kitPower, p);
        }
        return kitPower;
    }

    /** What the recipient will be told, for the window's preview. Null for an unknown kit. */
    public String recipientMessage(String kit, Person giver, String recipientName) {
        List<Kit> found = catalog.find(kit);
        if (found.size() != 1) return null;
        return message(found.get(0), rules.anonymousGivers() ? "An operator" : giver.name(), recipientName);
    }

    // ----- deciding -----------------------------------------------------------------------------------

    private Decision decide(GiveRequest r, boolean forGive) {
        if (r.kits().isEmpty() || r.recipientId() == null || r.source() == null || r.source().isBlank()) {
            return refuse(null, "invalid", "A gift needs a kit, a recipient and a source.", null);
        }
        if (r.source().length() > MAX_SOURCE) return refuse(null, "invalid", "The source is longer than " + MAX_SOURCE + " characters.", "source");

        // The kit, or the first in-season variant.
        Catalog c = catalog;
        Kit kit = null;
        List<Kit> resolved = new ArrayList<>();
        for (String id : r.kits()) {
            List<Kit> found = c.find(id);
            if (found.isEmpty()) return refuse(null, "unknown_kit", "There's no kit \"" + id + "\".", id);
            if (found.size() > 1) {
                List<String> q = new ArrayList<>();
                for (Kit k : found) q.add(k.qualifiedId());
                return refuse(null, "unknown_kit", "\"" + id + "\" is in several catalogs; name one: " + String.join(", ", q) + ".", id);
            }
            resolved.add(found.get(0));
        }
        for (Kit k : resolved) {
            if (k.occasion() != null && occasions.isOn(k.occasion())) { kit = k; break; }
        }
        if (kit == null) kit = resolved.get(resolved.size() - 1);

        Person recipient = players.online(r.recipientId());
        if (recipient == null) return refuse(kit, "recipient_offline", "The recipient isn't online.", "recipient=" + r.recipientId());

        if (r.fromServer()) {
            Refusal no = checkTrust(kit, r.source(), c);
            if (no != null) return new Decision(kit, recipient, no, null, List.of());
        } else if (!players.hasPermission(r.giver().id(), Ports.GIVE)) {
            return refuse(kit, "no_permission", "Giving gifts needs the " + Ports.GIVE + " permission.", "giver=" + r.giver().id());
        }

        if (kit.oncePerPlayer() && history.hasReceived(recipient.id(), kit.qualifiedId())) {
            return refuse(kit, "once_per_player", recipient.name() + " already has " + kit.name() + ", which is given once per player.", null);
        }
        if (kit.limits()) {
            Refusal no = limits(r, recipient);
            if (no != null) return new Decision(kit, recipient, no, null, List.of());
        }

        if (r.fromServer()) return new Decision(kit, recipient, null, null, itemPreview(kit, c));
        if (!runner.available()) return refuse(kit, "no_commanddeck", "Giving as a person needs CommandDeck 1.1.", null);
        // At give time an item list is checked with its actual roll, in start(); here, with every item at its most.
        if (forGive && !kit.isPreset()) return new Decision(kit, recipient, null, null, List.of());
        Ports.Runner.Plan plan = kit.isPreset() ? Ports.Runner.Plan.preset(kit.preset()) : Ports.Runner.Plan.lines(bestLines(kit, c));
        Ports.Runner.Check rc = runner.check(r.giver(), recipient, plan, r.source());
        if (!rc.ok()) return new Decision(kit, recipient, commanddeck(rc.code(), rc.message(), rc.detail()), rc, List.of());
        return new Decision(kit, recipient, null, rc, kit.isPreset() ? rc.preview() : itemPreview(kit, c));
    }

    private Refusal checkTrust(Kit kit, String caller, Catalog c) {
        if (kit.isPreset()) return new Refusal("preset_from_server", kit.name() + " runs commands, so it can only be given by a person.", null);
        Contents contents = Contents.of(kit, c);
        Integer kitPower = power(contents);
        Refusal best = null;
        for (Rules.Trust rule : rules.trusted()) {
            if (!rule.caller().equals(caller)) continue;
            if (!rule.kitSources().containsAll(contents.sources())) {
                List<String> missing = new ArrayList<>(contents.sources());
                missing.removeAll(rule.kitSources());
                best = new Refusal("not_trusted", caller + " may not give kits that reach " + String.join(", ", missing) + " from the server.", "sources=" + missing);
                continue;
            }
            if (kitPower == null) {
                boolean approved = rule.unratedKits().contains(kit.qualifiedId()) || rule.unratedSources().containsAll(contents.sources());
                if (approved) return null;
                best = new Refusal("unrated", kit.name() + " has items the power curve can't rate, and the operator hasn't approved it as a server present.", "kit=" + kit.qualifiedId());
                continue;
            }
            if (kitPower > rule.maxPower()) {
                best = new Refusal("over_power_cap", kit.name() + " is power " + kitPower + " at best, above the cap of " + rule.maxPower() + " for " + caller + ".", "power=" + kitPower + " cap=" + rule.maxPower());
                continue;
            }
            return null;
        }
        return best != null ? best : new Refusal("not_trusted", "The operator hasn't allowed " + caller + " to give gifts from the server.", "caller=" + caller);
    }

    private Refusal limits(GiveRequest r, Person recipient) {
        Rules ru = rules;
        Instant now = clock.instant();
        if (!r.fromServer() && ru.giverDailyCap() > 0 && history.givenOn(r.giver().id(), now, clock.getZone()) >= ru.giverDailyCap()) {
            return new Refusal("giver_daily_cap", "You've given " + ru.giverDailyCap() + " gifts today, the daily limit.", "cap=" + ru.giverDailyCap());
        }
        if (ru.recipientDailyCap() > 0 && history.receivedOn(recipient.id(), now, clock.getZone()) >= ru.recipientDailyCap()) {
            return new Refusal("recipient_daily_cap", recipient.name() + " has had " + ru.recipientDailyCap() + " gifts today, the daily limit.", "cap=" + ru.recipientDailyCap());
        }
        if (!r.fromServer() && !ru.pairCooldown().isZero()) {
            Instant last = history.lastBetween(r.giver().id(), recipient.id());
            if (last != null && last.plus(ru.pairCooldown()).isAfter(now)) {
                long wait = Duration.between(now, last.plus(ru.pairCooldown())).getSeconds() + 1;
                return new Refusal("cooldown", "You can give " + recipient.name() + " another gift in " + wait + " seconds.", "wait=" + wait);
            }
        }
        return null;
    }

    // ----- giving -------------------------------------------------------------------------------------

    private GiveStart start(GiveRequest r, String token, boolean confirmed, GiveListener listener) {
        Decision d = decide(r, true);
        if (d.refusal() != null) return new GiveStart(false, null, d.refusal());
        Kit kit = d.kit();
        Catalog c = catalog;

        // A preset that CommandDeck asks to confirm can't go to everyone at once: its confirmation covers one
        // target, and its prompt has to be shown as written.
        if (kit.isPreset() && d.runnerCheck().needsConfirmation() && confirmed) {
            return new GiveStart(false, null, new Refusal(CONFIRMS_EACH, CONFIRMS_EACH_MESSAGE, null));
        }
        // Otherwise a preset that asks for confirmation needs the token from check.
        if (kit.isPreset() && d.runnerCheck().needsConfirmation()) {
            Refusal no = useToken(token, r, kit);
            if (no != null) return new GiveStart(false, null, no);
        }

        String giftId = newId(8);
        Instant now = clock.instant();
        Long seed = null;
        Roller.Roll roll = null;
        if (!kit.isPreset()) {
            seed = random.nextLong();
            roll = Roller.roll(kit, c, seed, rules.maxItemsPerRoll());
            for (var issue : roll.issues()) serverLog.accept(issue.toString());
        }
        String giverId = r.fromServer() ? "server" : r.giver().id();
        history.begin(giftId, now, giverId, d.recipient().id(), kit.qualifiedId(), kit.limits());
        Finisher fin = new Finisher(giftId, now, r, kit, d.recipient(), seed, roll, listener);

        if (r.fromServer()) {
            inventory.give(d.recipient().id(), roll.items(), delivered -> fin.fromInventory(delivered));
            return new GiveStart(true, giftId, null);
        }

        Ports.Runner.Plan plan;
        String runToken = null;
        if (kit.isPreset()) {
            plan = Ports.Runner.Plan.preset(kit.preset());
            runToken = d.runnerCheck().token();
        } else {
            if (roll.items().isEmpty()) {
                fin.done(GiftResult.Delivery.FULL, Map.of(), List.of(), null);
                return new GiveStart(true, giftId, null);
            }
            plan = Ports.Runner.Plan.lines(giveLines(roll.items()));
            // The person saw the "may contain" preview; the roll is checked as it actually came out.
            Ports.Runner.Check rc = runner.check(r.giver(), d.recipient(), plan, r.source());
            if (!rc.ok()) {
                history.finish(giftId, false);
                return new GiveStart(false, null, commanddeck(rc.code(), rc.message(), rc.detail()));
            }
            if (rc.needsConfirmation() && !confirmed) {
                Refusal no = useToken(token, r, kit);
                if (no != null) {
                    history.finish(giftId, false);
                    return new GiveStart(false, null, no);
                }
            }
            runToken = rc.token();
        }
        Ports.Runner.Started s = runner.start(r.giver(), d.recipient(), plan, r.source(), runToken, ran -> fin.fromRunner(ran));
        if (!s.ok()) {
            history.finish(giftId, false);
            return new GiveStart(false, null, commanddeck(s.code(), s.message(), s.detail()));
        }
        return new GiveStart(true, giftId, null);
    }

    /** Finishes one gift: history, log, messages, listener. */
    private final class Finisher {
        final String giftId;
        final Instant time;
        final GiveRequest request;
        final Kit kit;
        final Person recipient;
        final Long seed;
        final Roller.Roll roll;
        final GiveListener listener;

        Finisher(String giftId, Instant time, GiveRequest request, Kit kit, Person recipient, Long seed, Roller.Roll roll, GiveListener listener) {
            this.giftId = giftId;
            this.time = time;
            this.request = request;
            this.kit = kit;
            this.recipient = recipient;
            this.seed = seed;
            this.roll = roll;
            this.listener = listener;
        }

        void fromInventory(Ports.Inventory.Delivered d) {
            long rolled = roll.total(), given = 0;
            for (long n : d.given().values()) given += n;
            GiftResult.Delivery how = given == 0 && rolled > 0 ? GiftResult.Delivery.NONE
                    : given < rolled ? GiftResult.Delivery.PARTIAL : GiftResult.Delivery.FULL;
            done(how, d.given(), List.of(), d.problem());
        }

        void fromRunner(Ports.Runner.Ran ran) {
            GiftResult.Delivery how = ran.completed() ? GiftResult.Delivery.FULL
                    : ran.sent().isEmpty() ? GiftResult.Delivery.NONE : GiftResult.Delivery.PARTIAL;
            Map<String, Long> given = roll == null ? Map.of() : how == GiftResult.Delivery.FULL ? roll.items() : sentItems(ran.sent(), roll.items());
            done(how, given, ran.sent(), ran.completed() ? null : ran.message());
        }

        void done(GiftResult.Delivery how, Map<String, Long> given, List<String> commands, String problem) {
            history.finish(giftId, how != GiftResult.Delivery.NONE);
            String giverName = request.fromServer() ? "server" : request.giver().name();
            String giverId = request.fromServer() ? "server" : request.giver().id();
            try {
                log.append(new GiftLog.Line(time, giftId, request.fromServer(), giverName, giverId, recipient.name(), recipient.id(),
                        kit.qualifiedId(), request.source(), how.name().toLowerCase(), kit.limits(), kit.oncePerPlayer(), seed,
                        request.reason(), given, commands));
            } catch (IOException e) {
                serverLog.accept("Generous Operator couldn't write " + log.file() + ": " + e.getMessage() + ". Gift " + giftId + " was " + how.name().toLowerCase() + ".");
            }
            if (how != GiftResult.Delivery.NONE) {
                String shown = shownGiver();
                String text = message(kit, shown, recipient.name());
                if (how == GiftResult.Delivery.PARTIAL) text += " Part of it couldn't be delivered.";
                else if (roll != null && given.isEmpty()) text += " It came up empty this time.";
                players.tell(recipient.id(), text);
                if (rules.announce()) players.announce(shown + " gave " + recipient.name() + " " + kit.name() + ".");
            }
            if (listener != null) {
                try {
                    listener.onFinish(new GiftResult(giftId, kit.qualifiedId(), recipient.id(), how, given, commands, problem));
                } catch (RuntimeException e) {
                    serverLog.accept("A gift listener (" + request.source() + ") threw " + e + "; ignored.");
                }
            }
        }

        String shownGiver() {
            if (request.fromServer()) return "The server";
            return rules.anonymousGivers() ? "An operator" : request.giver().name();
        }
    }

    static String message(Kit kit, String giver, String recipient) {
        String m = kit.message() != null ? kit.message() : "{giver} sent you " + kit.name() + "!";
        return m.replace("{giver}", giver).replace("{kit}", kit.name()).replace("{recipient}", recipient);
    }

    // ----- helpers ------------------------------------------------------------------------------------

    /** One {@code /give} line per item, as the giver would type it; CommandDeck fills {@code {target}}. */
    static List<String> giveLines(Map<String, Long> items) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : items.entrySet()) {
            out.add(e.getValue() == 1 ? "/give {target} " + e.getKey() : "/give {target} " + e.getKey() + " --quantity " + e.getValue());
        }
        return out;
    }

    /** The items whose lines were sent, for a run that stopped partway. */
    private static Map<String, Long> sentItems(List<String> sent, Map<String, Long> rolled) {
        Map<String, Long> out = new LinkedHashMap<>();
        List<String> lines = giveLines(rolled);
        int k = 0;
        for (Map.Entry<String, Long> e : rolled.entrySet()) {
            if (sent.contains(lines.get(k))) out.put(e.getKey(), e.getValue());
            k++;
        }
        return out;
    }

    /** Every item a kit can give, at its most: what the checks see before the roll. */
    private static List<String> bestLines(Kit kit, Catalog c) {
        Map<String, Long> best = new LinkedHashMap<>();
        for (Map.Entry<String, Contents.Range> e : Contents.of(kit, c).items().entrySet()) best.put(e.getKey(), Math.max(1, e.getValue().max()));
        return giveLines(best);
    }

    private List<String> itemPreview(Kit kit, Catalog c) {
        Contents contents = Contents.of(kit, c);
        boolean rolls = false;
        for (Contents.Range r : contents.items().values()) if (r.min() != r.max()) rolls = true;
        List<String> out = new ArrayList<>();
        if (rolls) out.add("May contain:");
        for (Map.Entry<String, Contents.Range> e : contents.items().entrySet()) {
            Contents.Range r = e.getValue();
            String n = r.min() == r.max() ? Long.toString(r.max()) : r.min() + "-" + r.max();
            out.add(n + " × " + items.name(e.getKey()) + " (" + e.getKey() + ")");
        }
        return out;
    }

    private Refusal useToken(String token, GiveRequest r, Kit kit) {
        if (token == null) return new Refusal("needs_confirmation", "This gift needs confirming first.", null);
        synchronized (tokens) {
            expireTokens();
            Pending p = tokens.get(token);
            if (p == null || !p.request().equals(r) || !p.kit().equals(kit.qualifiedId())) {
                return new Refusal("confirmation_mismatch", "That confirmation has expired or was for another gift.", null);
            }
            tokens.remove(token);
        }
        return null;
    }

    private void expireTokens() {
        Instant now = clock.instant();
        tokens.values().removeIf(p -> p.expires().isBefore(now));
    }

    private static Refusal commanddeck(String code, String message, String detail) {
        return new Refusal("commanddeck", message, code + (detail == null || detail.isEmpty() ? "" : " " + detail));
    }

    private static Decision refuse(Kit kit, String code, String message, String detail) {
        return new Decision(kit, null, new Refusal(code, message, detail), null, List.of());
    }

    private String newId(int hexChars) {
        StringBuilder b = new StringBuilder();
        while (b.length() < hexChars) b.append(Integer.toHexString(random.nextInt(16)));
        return b.toString();
    }
}
