package generousoperator.hytale.window;

import generousoperator.api.GiftResult;
import generousoperator.api.GiveCheck;
import generousoperator.api.GiveRequest;
import generousoperator.api.GiveStart;
import generousoperator.api.KitInfo;
import generousoperator.api.Person;
import generousoperator.api.Refusal;
import generousoperator.catalog.Contents;
import generousoperator.catalog.Kit;
import generousoperator.give.GiftLog;
import generousoperator.give.GiftService;
import generousoperator.give.Ports;
import generousoperator.json.Json;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * One player's Generous Operator window, as plain state plus rendering into
 * {@link UiOps}. Two tabs:
 *
 * <ul>
 * <li><b>Give:</b> who to give to (a player, or everyone online) on the left, above the kits, grouped and
 *     searchable; a kit that can't be given to that recipient now stays in the list, dimmed, with its reason.
 *     On the right, what the kit may contain, what the recipient will see, the equivalent command, and Give.
 * <li><b>Log:</b> the gift log, newest first, by day; on the right, one gift's details and Give again.
 * </ul>
 *
 * Every gift goes through {@link GiftService}, as the window's player, so the window has no rules of its own.
 * A gift's result arrives later (CommandDeck runs it over ticks), so it is pushed to the open page through the
 * sink the page attaches. Each redraw of the right pane bumps a generation number, and clicks from an older one
 * are ignored, because the client drops events while an update is unacknowledged.
 */
public final class GiftWindow {
    public static final String DIR = "GenerousOperator/";
    public static final String DOC = DIR + "Window.ui";
    public static final String LIST_ROW = DIR + "ListRow.ui";
    public static final String LIST_ROW_DIM = DIR + "ListRowDim.ui";
    public static final String GROUP_ROW = DIR + "GroupRow.ui";
    public static final String LIST_HEADING = DIR + "ListHeading.ui";
    public static final String NOTE_ROW = DIR + "NoteRow.ui";
    public static final String INFO_ROW = DIR + "InfoRow.ui";
    public static final String GIVE_FOOTER = DIR + "GiveFooter.ui";
    public static final String LOG_FOOTER = DIR + "LogFooter.ui";

    /** Every document the window uses (the plugin checks they are in the jar). */
    public static final List<String> DOCUMENTS = List.of(DOC, LIST_ROW, LIST_ROW_DIM, GROUP_ROW, LIST_HEADING, NOTE_ROW, INFO_ROW,
            GIVE_FOOTER, LOG_FOOTER);

    /** The recipient value for everyone online. */
    static final String ALL = "all";
    /** Log entries shown at once; "Load more" adds as many again. */
    static final int LOG_PAGE = 200;
    static final String REASON = "window";
    /** Groups in the order the design lists them; other groups follow in load order, and Other comes last. */
    private static final List<String> GROUP_ORDER = List.of("Boosters", "Builder", "Holidays", "Life", "Server", "Playtest");
    static final String IN_SEASON = "In season";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    public enum Tab {
        GIVE, LOG
    }

    /** One entry in the left list: a heading (null key), a group button, a kit or a log entry. */
    private record Entry(String key, String text, boolean dim, boolean group) {}

    /** A batch to everyone online, from its confirmation to its Done. */
    private static final class Batch {
        final String kit;
        final String kitName;
        final List<String> recipients;
        final Map<String, String> names = new HashMap<>();
        final List<String> results = new ArrayList<>();
        int reached;
        boolean stop;
        boolean finished;

        Batch(String kit, String kitName, List<String> recipients) {
            this.kit = kit;
            this.kitName = kitName;
            this.recipients = recipients;
        }
    }

    private final Host host;
    private Tab tab = Tab.GIVE;
    private String query = "";
    private String giveFilter = "all";
    private String logFilter = "all";
    private int gen;
    private String status = "";
    private Consumer<UiOps> sink;
    /** True while an event is handled: its own redraw shows whatever a synchronous result changed. */
    private boolean handling;

    // Give tab.
    private String recipient;
    private String kitKey;
    private final Set<String> collapsed = new HashSet<>();
    /** Each kit's check for the current recipient (or for the first player, for everyone); null when stale. */
    private Map<String, GiveCheck> checks;
    private String checkedFor;
    /** A gift waiting for Yes: one player's (with CommandDeck's token), or everyone's (batch is set, not started). */
    private GiveRequest pendingRequest;
    private String pendingToken;
    private boolean busy;
    private Batch batch;
    /** The batch was confirmed and is going (or has finished). */
    private boolean batchStarted;

    // Log tab.
    private List<GiftLog.Line> log = List.of();
    private int logShown = LOG_PAGE;
    private int logKey = -1;

    public GiftWindow(Host host) {
        this.host = host;
    }

    public synchronized Tab tab() {
        return tab;
    }

    public synchronized String status() {
        return status;
    }

    public synchronized String recipient() {
        return recipient;
    }

    public synchronized String selectedKit() {
        return kitKey;
    }

    public synchronized int generation() {
        return gen;
    }

    /** Where results that arrive after an event are sent; null when no page is open. */
    public synchronized void attach(Consumer<UiOps> sink) {
        this.sink = sink;
    }

    public synchronized void detach(Consumer<UiOps> sink) {
        if (this.sink == sink) this.sink = null;
    }

    /** Everything after the main document is appended: the static bindings plus both sides. */
    public synchronized UiOps open() {
        UiOps ops = new UiOps();
        for (Tab t : Tab.values()) ops.bind("#Tab" + cap(t), "Activating", Map.of("Action", "tab", "Tab", t.name()));
        ops.bind("#To", "ValueChanged", Map.of("Action", "to", "@Value", "#To.Value"));
        ops.bind("#Search", "ValueChanged", Map.of("Action", "search", "@Query", "#Search.Value"));
        ops.bind("#Filter", "ValueChanged", Map.of("Action", "filter", "@Value", "#Filter.Value"));
        ops.bind("#ConfirmYes", "Activating", Map.of("Action", "confirm"));
        ops.bind("#ConfirmNo", "Activating", Map.of("Action", "cancel"));
        // A question left open when the page was closed is dropped; a batch that is going carries on.
        clearPending();
        checks = null;
        if (batch == null || batch.finished) status = permissionProblem();
        if (tab == Tab.LOG) loadLog();
        renderAll(ops);
        return ops;
    }

    /** Whether the page should redraw this event's result only once typing pauses (see {@link #deferredOps}). */
    public static boolean deferred(String action) {
        return "search".equals(action);
    }

    /** The redraw for a deferred event, built from the latest state. */
    public synchronized UiOps deferredOps(String action) {
        UiOps ops = new UiOps();
        renderList(ops);
        return ops;
    }

    /** A client event: its {@code Action} and the rest of its data. */
    public record Event(String action, Map<String, Object> data) {}

    /** Decodes a page event's data (a JSON object). Throws if it isn't one. */
    public static Event decode(String rawData) {
        Object parsed = Json.parse(rawData);
        if (!(parsed instanceof Map<?, ?> m)) throw new IllegalArgumentException("not a JSON object");
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) data.put(String.valueOf(e.getKey()), e.getValue());
        Object a = data.remove("Action");
        return new Event(a == null ? null : a.toString(), data);
    }

    /** Handles one event from the page. The returned ops are sent as a partial update (possibly none). */
    public synchronized UiOps onEvent(String action, Map<String, Object> data) {
        UiOps ops = new UiOps();
        if (action == null) return ops;
        handling = true;
        try {
            switch (action) {
                case "tab" -> switchTab(ops, str(data.get("Tab")));
                case "search" -> query = str(data.get("@Query"));
                case "filter" -> {
                    String f = str(data.get("@Value"));
                    if (f.isEmpty()) f = "all";
                    if (tab == Tab.GIVE) giveFilter = f;
                    else logFilter = f;
                    renderList(ops);
                }
                case "to" -> {
                    String to = str(data.get("@Value"));
                    if (!to.isEmpty() && !to.equals(recipient) && !running()) {
                        recipient = to;
                        checks = null;
                        clearPending();
                        status = "";
                        hideConfirm(ops);
                        renderList(ops);
                        renderRight(ops);
                    }
                }
                case "pick" -> pick(ops, str(data.get("Key")));
                case "group" -> {
                    String g = str(data.get("Group"));
                    if (!collapsed.remove(g)) collapsed.add(g);
                    renderList(ops);
                }
                case "more" -> {
                    logShown += LOG_PAGE;
                    renderList(ops);
                }
                case "give" -> {
                    if (sameGen(data)) give(ops);
                }
                case "confirm" -> confirm(ops);
                case "cancel" -> {
                    clearPending();
                    hideConfirm(ops);
                    status = "";
                    renderRight(ops);
                }
                case "stop" -> {
                    if (sameGen(data) && batch != null && !batch.finished) {
                        batch.stop = true;
                        status = "Stopping after the current player...";
                        ops.text("#Status", status);
                        ops.set("#Footer[0] #Stop.Disabled", true);
                    }
                }
                case "done" -> {
                    if (sameGen(data) && batch != null && batch.finished) {
                        batch = null;
                        status = "";
                        renderList(ops);
                        renderRight(ops);
                    }
                }
                case "giveAgain" -> {
                    if (sameGen(data)) giveAgain(ops);
                }
                default -> {
                }
            }
        } finally {
            handling = false;
        }
        return ops;
    }

    private boolean sameGen(Map<String, Object> data) {
        return String.valueOf(gen).equals(str(data.get("Gen")));
    }

    private String g() {
        return Integer.toString(gen);
    }

    // ----- switching --------------------------------------------------------------------------------------

    private void switchTab(UiOps ops, String name) {
        Tab t;
        try {
            t = Tab.valueOf(name);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (t == tab) return;
        tab = t;
        query = "";
        clearPending();
        if (batch == null || batch.finished) status = tab == Tab.GIVE ? permissionProblem() : "";
        if (tab == Tab.LOG) loadLog();
        else checks = null;
        renderAll(ops);
    }

    private void pick(UiOps ops, String key) {
        if (key.isEmpty()) return;
        if (tab == Tab.GIVE) {
            if (running() || key.equals(kitKey)) return;
            kitKey = key;
        } else {
            int i = parseInt(key, -1);
            if (i < 0 || i >= log.size() || i == logKey) return;
            logKey = i;
        }
        clearPending();
        hideConfirm(ops);
        status = "";
        renderList(ops);
        renderRight(ops);
    }

    private void giveAgain(UiOps ops) {
        if (logKey < 0 || logKey >= log.size()) return;
        GiftLog.Line l = log.get(logKey);
        tab = Tab.GIVE;
        query = "";
        checks = null;
        kitKey = host.service().catalog().get(l.kit()) != null ? l.kit() : null;
        if (host.players().online(l.recipientId()) != null) {
            recipient = l.recipientId();
            status = kitKey == null ? "That kit isn't in the catalogs any more." : "";
        } else {
            status = l.recipient() + " isn't online now, so choose who to give it to.";
        }
        renderAll(ops);
    }

    // ----- giving -------------------------------------------------------------------------------------

    private void give(UiOps ops) {
        if (busy || running() || kitKey == null) return;
        GiftService service = host.service();
        if (everyone()) {
            List<String> will = new ArrayList<>();
            Refusal kitWide = null;
            for (Person p : online()) {
                GiveCheck c = service.check(request(p.id()));
                if (c.ok() && c.needsConfirmation()) {
                    kitWide = new Refusal(GiftService.CONFIRMS_EACH, GiftService.CONFIRMS_EACH_MESSAGE, null);
                    break;
                }
                if (c.ok()) will.add(p.id());
                else if (!perRecipient(c.refusal())) {
                    kitWide = c.refusal();
                    break;
                }
            }
            if (kitWide != null || will.isEmpty()) {
                status = kitWide != null ? kitWide.message() : "Nobody online can be given this kit now.";
                renderRight(ops);
                return;
            }
            batch = new Batch(kitKey, kitName(kitKey), will);
            for (Person p : online()) batch.names.put(p.id(), p.name());
            showConfirm(ops, "Give " + batch.kitName + " to " + count(will.size(), "player") + "?", "");
            return;
        }
        GiveRequest r = request(recipient);
        GiveCheck c = service.check(r);
        if (!c.ok()) {
            checks = null;
            status = c.refusal().message();
            renderList(ops);
            renderRight(ops);
            return;
        }
        if (c.needsConfirmation()) {
            pendingRequest = r;
            pendingToken = c.confirmToken();
            // CommandDeck's prompt, word for word.
            showConfirm(ops, c.confirmPrompt(), "");
            return;
        }
        start(ops, r, null);
    }

    private void confirm(UiOps ops) {
        hideConfirm(ops);
        if (batch != null && !batch.finished && batch.reached == 0 && !batchStarted) {
            startBatch(ops);
            return;
        }
        if (pendingRequest == null) return;
        GiveRequest r = pendingRequest;
        String token = pendingToken;
        clearPending();
        start(ops, r, token);
    }

    private void start(UiOps ops, GiveRequest r, String token) {
        busy = true;
        status = "Giving...";
        String kit = kitKey;
        GiveStart s = host.service().give(r, token, result -> finished(kit, result));
        if (!s.started()) {
            busy = false;
            status = s.refusal().message();
        }
        checks = null;
        renderList(ops);
        renderRight(ops);
    }

    /** A single gift's result, from the tick thread (or at once, for a gift that finishes in the same call). */
    private synchronized void finished(String kit, GiftResult result) {
        busy = false;
        checks = null;
        status = resultText(kitName(kit), name(result.recipientId()), result);
        push();
    }

    private void startBatch(UiOps ops) {
        Batch b = batch;
        batchStarted = true;
        status = "Giving 1 of " + b.recipients.size() + "...";
        host.service().giveAll(host.giver(), b.recipients, List.of(b.kit), REASON, GiftService.OWN_SOURCE,
                (id, outcome) -> batchStep(b, id, outcome), () -> stopRequested(b), done -> batchDone(b, done));
        renderRight(ops);
    }

    private synchronized boolean stopRequested(Batch b) {
        return b.stop;
    }

    private synchronized void batchStep(Batch b, String id, Object outcome) {
        b.reached++;
        String who = b.names.getOrDefault(id, id);
        if (outcome instanceof GiftResult r) {
            b.results.add(who + ": " + delivery(r));
        } else if (outcome instanceof Refusal no) {
            b.results.add(who + ": " + ("recipient_offline".equals(no.code()) ? "left before their turn" : "skipped (" + shortReason(no) + ")"));
        }
        if (b == batch && !b.finished && b.reached < b.recipients.size()) {
            status = "Giving " + (b.reached + 1) + " of " + b.recipients.size() + "...";
            if (sink != null && !handling) {
                UiOps ops = new UiOps();
                ops.text("#Status", status);
                sink.accept(ops);
            }
        }
    }

    private synchronized void batchDone(Batch b, GiftService.BatchResult done) {
        for (String id : done.notReached()) b.results.add(b.names.getOrDefault(id, id) + ": not given (stopped)");
        b.finished = true;
        batchStarted = false;
        checks = null;
        status = "Gave " + b.kitName + " to " + count(done.given().size(), "player") + (done.skipped().isEmpty() ? "" : ", skipped " + done.skipped().size())
                + (done.notReached().isEmpty() ? "" : ", stopped before " + done.notReached().size()) + ".";
        push();
    }

    /** Redraws what a late result changed, on the open page. */
    private void push() {
        if (sink == null || handling) return;
        UiOps ops = new UiOps();
        renderList(ops);
        renderRight(ops);
        sink.accept(ops);
    }

    private GiveRequest request(String recipientId) {
        return GiveRequest.asPerson(kitKey, recipientId, host.giver(), REASON, GiftService.OWN_SOURCE);
    }

    private boolean running() {
        return batch != null && !batch.finished && batchStarted;
    }

    private void clearPending() {
        pendingRequest = null;
        pendingToken = null;
        if (batch != null && !batchStarted && !batch.finished) batch = null;
    }

    // ----- checks -------------------------------------------------------------------------------------

    private boolean everyone() {
        return ALL.equals(recipient);
    }

    private List<Person> online() {
        List<Person> all = new ArrayList<>(host.players().everyoneOnline());
        all.sort(Comparator.comparing(p -> p.name().toLowerCase(Locale.ROOT)));
        return all;
    }

    /** The recipient, still online, or the window's own player, or everyone. */
    private void settleRecipient() {
        if (everyone()) return;
        if (recipient != null && host.players().online(recipient) != null) return;
        String me = host.giver().id();
        recipient = host.players().online(me) != null ? me : ALL;
        checks = null;
    }

    private Map<String, GiveCheck> checks() {
        settleRecipient();
        if (checks != null && recipient.equals(checkedFor)) return checks;
        checks = new HashMap<>();
        checkedFor = recipient;
        String probe = everyone() ? firstOnline() : recipient;
        if (probe == null) return checks;
        GiftService service = host.service();
        for (KitInfo k : service.kits()) {
            checks.put(k.kit(), service.check(GiveRequest.asPerson(k.kit(), probe, host.giver(), REASON, GiftService.OWN_SOURCE)));
        }
        return checks;
    }

    private String firstOnline() {
        List<Person> all = online();
        return all.isEmpty() ? null : all.get(0).id();
    }

    /** Why this kit can't be given to the chosen recipient (for everyone: to anyone at all), or null. */
    private Refusal refusal(String kit) {
        if (everyone() && firstOnline() == null) return new Refusal("nobody", "Nobody is online.", null);
        GiveCheck c = checks().get(kit);
        if (c == null) return null;
        if (everyone()) {
            if (c.ok() && c.needsConfirmation()) return new Refusal(GiftService.CONFIRMS_EACH, GiftService.CONFIRMS_EACH_MESSAGE, null);
            if (!c.ok() && !perRecipient(c.refusal())) return c.refusal();
            return null;
        }
        return c.ok() ? null : c.refusal();
    }

    /** Refusals that depend on the recipient: a gift to everyone skips that player and goes on. */
    static boolean perRecipient(Refusal r) {
        return switch (r.code()) {
            case "recipient_offline", "once_per_player", "recipient_daily_cap", "cooldown" -> true;
            default -> false;
        };
    }

    /** The few words after a dimmed kit's name. */
    static String shortReason(Refusal r) {
        return switch (r.code()) {
            case "once_per_player" -> "already received";
            case "recipient_daily_cap", "giver_daily_cap" -> "cap reached";
            case "cooldown" -> "cooldown " + waitText(r.detail());
            case "no_permission" -> "needs permission";
            case "recipient_offline" -> "offline";
            case "no_commanddeck" -> "needs CommandDeck";
            case "commanddeck" -> "CommandDeck refuses";
            case GiftService.CONFIRMS_EACH -> "confirms each player: choose one player";
            case "nobody" -> "nobody online";
            default -> "can't give now";
        };
    }

    private static String waitText(String detail) {
        long s = detail != null && detail.startsWith("wait=") ? parseLong(detail.substring(5)) : 0;
        if (s >= 3600) return (s + 3599) / 3600 + "h";
        if (s >= 60) return (s + 59) / 60 + "m";
        return Math.max(s, 1) + "s";
    }

    /** What stops this player giving at all, or "". */
    private String permissionProblem() {
        Ports.Players players = host.players();
        String me = host.giver().id();
        if (!players.hasPermission(me, Ports.GIVE)) return "You need the " + Ports.GIVE + " permission to give gifts.";
        if (!players.hasPermission(me, "commanddeck.use")) return "You need commanddeck.use to give gifts: they run through CommandDeck.";
        return "";
    }

    // ----- rendering ----------------------------------------------------------------------------------

    private void renderAll(UiOps ops) {
        for (Tab t : Tab.values()) ops.set("#Tab" + cap(t) + ".Disabled", t == tab);
        ops.visible("#ToRow", tab == Tab.GIVE);
        ops.set("#Search.Value", query);
        ops.entries("#Filter", filterEntries());
        ops.set("#Filter.Value", tab == Tab.GIVE ? giveFilter : logFilter);
        hideConfirm(ops);
        renderList(ops);
        renderRight(ops);
    }

    private List<UiOps.Entry> filterEntries() {
        List<UiOps.Entry> out = new ArrayList<>();
        if (tab == Tab.LOG) {
            out.add(new UiOps.Entry("Everything", "all"));
            out.add(new UiOps.Entry("Given by me", "mine"));
            out.add(new UiOps.Entry("To the player chosen on Give", "recipient"));
            out.add(new UiOps.Entry("Server presents", "server"));
            out.add(new UiOps.Entry("Partial and undelivered", "problems"));
            return out;
        }
        out.add(new UiOps.Entry("All kits", "all"));
        out.add(new UiOps.Entry("In season", "season"));
        out.add(new UiOps.Entry("Can give now", "ok"));
        for (String g : groups(host.service().kits())) out.add(new UiOps.Entry("Group: " + g, "group:" + g));
        Set<String> sources = new java.util.TreeSet<>();
        for (KitInfo k : host.service().kits()) sources.add(k.source());
        for (String s : sources) out.add(new UiOps.Entry("From " + sourceName(s), "source:" + s));
        return out;
    }

    void renderList(UiOps ops) {
        ops.clear("#List");
        List<Entry> entries = tab == Tab.GIVE ? kitEntries(ops) : logEntries(ops);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String sel = "#List[" + i + "]";
            if (e.key() == null) {
                ops.append("#List", LIST_HEADING);
                ops.text(sel, e.text());
                continue;
            }
            if (e.group()) {
                ops.append("#List", GROUP_ROW);
                ops.text(sel + " #Button #Label", e.text());
                ops.bind(sel + " #Button", "Activating", Map.of("Action", "group", "Group", e.key()));
                continue;
            }
            ops.append("#List", e.dim() ? LIST_ROW_DIM : LIST_ROW);
            ops.text(sel + " #Button #Label", e.text());
            boolean selected = tab == Tab.GIVE ? e.key().equals(kitKey) : e.key().equals(Integer.toString(logKey));
            ops.set(sel + " #Button.Disabled", selected);
            if ("more".equals(e.key())) ops.bind(sel + " #Button", "Activating", Map.of("Action", "more"));
            else ops.bind(sel + " #Button", "Activating", Map.of("Action", "pick", "Key", e.key()));
        }
    }

    private List<Entry> kitEntries(UiOps ops) {
        renderTo(ops);
        List<KitInfo> all = host.service().kits();
        Ports.Occasions occasions = host.service().occasions();
        String q = query.trim().toLowerCase(Locale.ROOT);
        Map<String, List<KitInfo>> blocks = new LinkedHashMap<>();
        blocks.put(IN_SEASON, new ArrayList<>());
        for (String gname : groups(all)) blocks.put(gname, new ArrayList<>());
        int inSeason = 0;
        int shown = 0;
        for (KitInfo k : all) {
            boolean season = k.occasion() != null && occasions.isOn(k.occasion());
            if (season) inSeason++;
            if (!q.isEmpty() && !matches(k, q)) continue;
            if (giveFilter.equals("season") && !season) continue;
            if (giveFilter.equals("ok") && refusal(k.kit()) != null) continue;
            if (giveFilter.startsWith("group:") && !k.group().equals(giveFilter.substring(6))) continue;
            if (giveFilter.startsWith("source:") && !k.source().equals(giveFilter.substring(7))) continue;
            shown++;
            blocks.get(season ? IN_SEASON : k.group()).add(k);
        }
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, List<KitInfo>> b : blocks.entrySet()) {
            List<KitInfo> kits = b.getValue();
            if (kits.isEmpty()) continue;
            // By kind, so each ladder (potions, food) stays together, then by tier, then by name.
            kits.sort(Comparator.comparing((KitInfo k) -> k.kind() == null ? "￿" : k.kind())
                    .thenComparing(k -> k.tier() == null ? Integer.MAX_VALUE : k.tier())
                    .thenComparing(k -> k.name().toLowerCase(Locale.ROOT)));
            boolean open = !collapsed.contains(b.getKey()) || !q.isEmpty();
            out.add(new Entry(b.getKey(), (open ? "- " : "+ ") + b.getKey() + " (" + kits.size() + ")", false, true));
            if (!open) continue;
            for (KitInfo k : kits) {
                Refusal no = refusal(k.kit());
                out.add(new Entry(k.kit(), no == null ? k.name() : k.name() + ": " + shortReason(no), no != null, false));
            }
        }
        String counted = shown == all.size() ? count(all.size(), "kit") : shown + " of " + count(all.size(), "kit");
        ops.text("#Count", all.isEmpty() ? "No kits yet" : counted + (inSeason > 0 ? ", " + inSeason + " in season" : ""));
        return out;
    }

    private void renderTo(UiOps ops) {
        settleRecipient();
        List<Person> people = online();
        List<UiOps.Entry> to = new ArrayList<>();
        to.add(new UiOps.Entry("Everyone online (" + people.size() + ")", ALL));
        String me = host.giver().id();
        for (Person p : people) to.add(new UiOps.Entry(p.id().equals(me) ? p.name() + " (you)" : p.name(), p.id()));
        ops.entries("#To", to);
        ops.set("#To.Value", recipient);
    }

    private static boolean matches(KitInfo k, String q) {
        if (k.name().toLowerCase(Locale.ROOT).contains(q) || k.kit().toLowerCase(Locale.ROOT).contains(q)) return true;
        if (k.group().toLowerCase(Locale.ROOT).contains(q) || k.source().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (String t : k.tags()) if (t.toLowerCase(Locale.ROOT).contains(q)) return true;
        return false;
    }

    private static List<String> groups(List<KitInfo> kits) {
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (KitInfo k : kits) seen.add(k.group());
        List<String> out = new ArrayList<>();
        for (String gname : GROUP_ORDER) if (seen.remove(gname)) out.add(gname);
        boolean other = seen.remove(Kit.OTHER_GROUP);
        out.addAll(seen);
        if (other) out.add(Kit.OTHER_GROUP);
        return out;
    }

    void renderRight(UiOps ops) {
        gen++;
        ops.clear("#Rows");
        ops.clear("#Footer");
        ops.text("#Status", status);
        if (tab == Tab.GIVE) renderKit(ops);
        else renderLogEntry(ops);
    }

    private void renderKit(UiOps ops) {
        if (batch != null && batchStarted || batch != null && batch.finished) {
            renderBatch(ops);
            return;
        }
        KitInfo k = kitKey == null ? null : info(kitKey);
        if (k == null) {
            kitKey = null;
            ops.text("#Heading", "Give a gift");
            ops.text("#Meta", "");
            note(ops, 0, host.service().kits().isEmpty()
                    ? "There are no kits yet. Mods add them with a catalog; yours go in mods/Hledjolf_GenerousOperator/catalogs/."
                    : "Choose who to give to, then a kit on the left. You'll see what it may contain before anything is given.");
            ops.text("#Preview", "");
            ops.text("#PreviewNote", "");
            return;
        }
        ops.text("#Heading", k.name());
        ops.text("#Meta", meta(k));
        int r = 0;
        Refusal no = refusal(k.kit());
        if (everyone()) r = recipientsBlock(ops, r, k);
        if (k.description() != null && !k.description().isBlank()) r = note(ops, r, k.description());
        r = contents(ops, r, k);
        String to = everyone() ? firstOnline() : recipient;
        if (to != null) {
            Person p = host.players().online(to);
            String msg = host.service().recipientMessage(k.kit(), host.giver(), p == null ? "" : p.name());
            if (msg != null && p != null) {
                r = note(ops, r, (everyone() ? "Each player will see: \"" : p.name() + " will see: \"") + msg + "\"");
            }
        }
        ops.text("#Preview", "/" + host.command() + " " + (everyone() ? ALL : nameOf(recipient)) + " " + typedId(k));
        GiveCheck c = checks().get(k.kit());
        String note;
        if (everyone()) note = "Runs as you, for one player after another. Asks once for everyone.";
        else if (c != null && c.ok() && c.needsConfirmation()) note = "Runs as you. CommandDeck asks you to confirm this preset.";
        else note = "Runs as you. Given in one go; nothing to confirm.";
        ops.text("#PreviewNote", note);
        if (no != null && status.isEmpty()) ops.text("#Status", no.message());
        ops.append("#Footer", GIVE_FOOTER);
        ops.bind("#Footer[0] #Give", "Activating", Map.of("Action", "give", "Gen", g()));
        ops.set("#Footer[0] #Give.Disabled", busy || no != null);
        ops.visible("#Footer[0] #Stop", false);
        ops.visible("#Footer[0] #Done", false);
    }

    /** Before giving to everyone: who will get it, and who will be skipped and why. */
    private int recipientsBlock(UiOps ops, int r, KitInfo k) {
        List<Person> people = online();
        List<String> skipped = new ArrayList<>();
        int will = 0;
        for (Person p : people) {
            GiveCheck c = host.service().check(GiveRequest.asPerson(k.kit(), p.id(), host.giver(), REASON, GiftService.OWN_SOURCE));
            if (c.ok()) will++;
            else skipped.add(p.name() + " (" + shortReason(c.refusal()) + ")");
        }
        String text = people.size() + " online · " + will + " will receive it";
        if (!skipped.isEmpty()) text += " · " + skipped.size() + " skipped: " + String.join(", ", skipped);
        return note(ops, r, text);
    }

    private int contents(UiOps ops, int r, KitInfo k) {
        Kit kit = host.service().catalog().get(k.kit());
        if (kit == null) return r;
        if (kit.isPreset()) {
            GiveCheck c = checks().get(k.kit());
            r = heading(ops, r, "Runs these commands");
            if (c != null && c.ok() && !c.preview().isEmpty()) {
                for (String line : c.preview()) r = note(ops, r, line);
            } else {
                r = note(ops, r, "CommandDeck preset " + kit.preset());
            }
            return r;
        }
        Contents contents = Contents.of(kit, host.service().catalog());
        boolean rolls = false;
        for (Contents.Range range : contents.items().values()) if (range.min() != range.max()) rolls = true;
        r = heading(ops, r, rolls ? "May contain" : "Contains");
        if (contents.items().isEmpty()) return note(ops, r, "Nothing: every line refers to an item or table that isn't loaded.");
        for (Map.Entry<String, Contents.Range> e : contents.items().entrySet()) {
            Contents.Range range = e.getValue();
            String n = range.min() == range.max() ? Long.toString(range.max())
                    : range.min() == 0 ? "up to " + range.max() : range.min() + " to " + range.max();
            r = note(ops, r, host.itemName(e.getKey()) + " x " + n);
        }
        return r;
    }

    private void renderBatch(UiOps ops) {
        Batch b = batch;
        ops.text("#Heading", b.kitName);
        ops.text("#Meta", "To everyone online · " + count(b.recipients.size(), "player"));
        int r = heading(ops, 0, b.finished ? "Results" : "Given so far");
        for (String line : b.results) r = note(ops, r, line);
        ops.text("#Preview", "/" + host.command() + " " + ALL + " " + typedId(info(b.kit)));
        ops.text("#PreviewNote", b.finished ? "" : "Runs as you, for one player after another.");
        ops.append("#Footer", GIVE_FOOTER);
        ops.visible("#Footer[0] #Give", false);
        ops.visible("#Footer[0] #Stop", !b.finished);
        ops.visible("#Footer[0] #Done", b.finished);
        ops.set("#Footer[0] #Stop.Disabled", b.stop);
        ops.bind("#Footer[0] #Stop", "Activating", Map.of("Action", "stop", "Gen", g()));
        ops.bind("#Footer[0] #Done", "Activating", Map.of("Action", "done", "Gen", g()));
    }

    private String meta(KitInfo k) {
        List<String> parts = new ArrayList<>();
        parts.add(k.group());
        parts.add("from " + sourceName(k.source()));
        if (k.form().equals("preset")) {
            parts.add("runs commands");
        } else {
            Integer power = host.service().power(k.kit());
            parts.add(power == null ? "power: unrated" : "power: zone " + power);
        }
        if (k.oncePerPlayer()) parts.add("once per player");
        if (!k.limits()) parts.add("not counted toward limits");
        return String.join(" · ", parts);
    }

    // ----- the log ------------------------------------------------------------------------------------

    private void loadLog() {
        int[] skipped = {0};
        try {
            List<GiftLog.Line> lines = new ArrayList<>(host.readLog(skipped));
            java.util.Collections.reverse(lines);
            log = lines;
            if (skipped[0] > 0) status = count(skipped[0], "damaged line") + " in the gift log were skipped.";
        } catch (IOException e) {
            log = List.of();
            status = "Can't read the gift log: " + e.getMessage();
        }
        logShown = LOG_PAGE;
        logKey = log.isEmpty() ? -1 : 0;
    }

    private List<Entry> logEntries(UiOps ops) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        LocalDate today = LocalDate.ofInstant(host.service().clock().instant(), host.zone());
        List<Entry> out = new ArrayList<>();
        String day = null;
        int matching = 0;
        int listed = 0;
        for (int i = 0; i < log.size(); i++) {
            GiftLog.Line l = log.get(i);
            if (!keep(l, q)) continue;
            matching++;
            if (listed >= logShown) continue;
            listed++;
            ZonedDateTime t = l.time().atZone(host.zone());
            LocalDate d = t.toLocalDate();
            String block = d.equals(today) ? "Today" : d.equals(today.minusDays(1)) ? "Yesterday" : "Earlier";
            if (!block.equals(day)) {
                day = block;
                out.add(new Entry(null, block, false, false));
            }
            String when = block.equals("Earlier") ? WHEN.format(t) : TIME.format(t);
            String text = when + " " + giverName(l) + " to " + l.recipient() + ": " + kitName(l.kit()) + (l.delivery().equals("full") ? "" : " (" + l.delivery() + ")");
            out.add(new Entry(Integer.toString(i), text, !l.delivery().equals("full"), false));
        }
        if (matching > listed) out.add(new Entry("more", "Load more (" + (matching - listed) + " older)", false, false));
        ops.text("#Count", matching == log.size() ? count(log.size(), "entry") : matching + " of " + count(log.size(), "entry"));
        return out;
    }

    private boolean keep(GiftLog.Line l, String q) {
        String me = host.giver().id();
        switch (logFilter) {
            case "mine" -> { if (!me.equals(l.giverId())) return false; }
            case "recipient" -> { if (recipient == null || everyone() || !recipient.equals(l.recipientId())) return false; }
            case "server" -> { if (!l.fromServer()) return false; }
            case "problems" -> { if (l.delivery().equals("full")) return false; }
            default -> { }
        }
        if (q.isEmpty()) return true;
        for (String s : new String[] {l.giver(), l.recipient(), l.kit(), kitName(l.kit()), l.source()}) {
            if (s != null && s.toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private void renderLogEntry(UiOps ops) {
        if (logKey < 0 || logKey >= log.size()) {
            ops.text("#Heading", "The gift log");
            ops.text("#Meta", "");
            note(ops, 0, log.isEmpty() ? "No gifts yet. Every gift is logged here: who gave what, to whom, and what it contained."
                    : "Choose an entry on the left.");
            ops.text("#Preview", "/" + host.command() + " log");
            ops.text("#PreviewNote", "");
            return;
        }
        GiftLog.Line l = log.get(logKey);
        ops.text("#Heading", giverName(l) + " to " + l.recipient() + ": " + kitName(l.kit()));
        ops.text("#Meta", WHEN.format(l.time().atZone(host.zone())) + " · " + (l.fromServer() ? "from the server" : "as a person") + " · " + l.delivery());
        int r = 0;
        r = info(ops, r, "Kit", l.kit());
        r = info(ops, r, "Way", l.fromServer() ? "from the server, asked by " + sourceName(l.source()) : "as a person, by " + l.giver() + ", through CommandDeck");
        if (l.reason() != null && !l.reason().isBlank()) r = info(ops, r, "Reason", l.reason());
        if (l.seed() != null) r = info(ops, r, "Seed", Long.toString(l.seed()));
        r = info(ops, r, "Delivery", l.delivery());
        boolean first = true;
        for (Map.Entry<String, Long> e : l.items().entrySet()) {
            r = info(ops, r, first ? "Given" : "", host.itemName(e.getKey()) + " x " + e.getValue());
            first = false;
        }
        if (l.items().isEmpty() && l.seed() != null) r = info(ops, r, "Given", "nothing (it came up empty)");
        first = true;
        for (String c : l.commands()) {
            r = info(ops, r, first ? "Commands" : "", c);
            first = false;
        }
        ops.text("#Preview", "/" + host.command() + " log " + l.recipient());
        ops.text("#PreviewNote", "");
        if (!l.fromServer()) {
            ops.append("#Footer", LOG_FOOTER);
            ops.bind("#Footer[0] #GiveAgain", "Activating", Map.of("Action", "giveAgain", "Gen", g()));
        }
    }

    // ----- small pieces -------------------------------------------------------------------------------

    private int note(UiOps ops, int r, String text) {
        ops.append("#Rows", NOTE_ROW);
        ops.text("#Rows[" + r + "]", text);
        return r + 1;
    }

    private int heading(UiOps ops, int r, String text) {
        ops.append("#Rows", LIST_HEADING);
        ops.text("#Rows[" + r + "]", text);
        return r + 1;
    }

    private int info(UiOps ops, int r, String key, String value) {
        ops.append("#Rows", INFO_ROW);
        ops.text("#Rows[" + r + "] #Key", key);
        ops.text("#Rows[" + r + "] #Value", value);
        return r + 1;
    }

    private void showConfirm(UiOps ops, String text, String risk) {
        ops.visible("#ConfirmBox", true);
        ops.text("#ConfirmText", text);
        ops.text("#ConfirmRisk", risk);
        ops.visible("#ConfirmRisk", !risk.isEmpty());
        ops.set("#Footer[0] #Give.Disabled", true);
    }

    private static void hideConfirm(UiOps ops) {
        ops.visible("#ConfirmBox", false);
    }

    private KitInfo info(String kit) {
        for (KitInfo k : host.service().kits()) if (k.kit().equals(kit)) return k;
        return null;
    }

    private String kitName(String kit) {
        KitInfo k = info(kit);
        return k == null ? kit : k.name();
    }

    /** The id as typed in the command: the short one when it's unique, else the full one. */
    private String typedId(KitInfo k) {
        if (k == null) return "";
        Kit kit = host.service().catalog().get(k.kit());
        if (kit != null && host.service().catalog().find(kit.id()).size() == 1) return kit.id();
        return k.kit();
    }

    private String name(String playerId) {
        Person p = host.players().online(playerId);
        return p == null ? "the recipient" : p.name();
    }

    private String nameOf(String playerId) {
        Person p = playerId == null ? null : host.players().online(playerId);
        return p == null ? "<player>" : p.name();
    }

    private static String giverName(GiftLog.Line l) {
        return l.fromServer() ? "Server" : l.giver();
    }

    static String sourceName(String source) {
        if (GiftService.OWN_SOURCE.equals(source)) return "Generous Operator";
        if (Kit.SERVER.equals(source)) return "this server";
        int colon = source.indexOf(':');
        return colon < 0 ? source : source.substring(colon + 1);
    }

    private static String resultText(String kit, String who, GiftResult r) {
        return switch (r.delivery()) {
            case FULL -> r.items().isEmpty() && r.commands().isEmpty() ? "Given to " + who + ": " + kit + ", but it came up empty this time."
                    : "Given to " + who + ": " + kit + " (full).";
            case PARTIAL -> "Given to " + who + ": " + kit + ", partly delivered." + (r.message() == null ? "" : " " + r.message());
            case NONE -> "Couldn't give " + kit + " to " + who + "." + (r.message() == null ? "" : " " + r.message());
        };
    }

    private static String delivery(GiftResult r) {
        return switch (r.delivery()) {
            case FULL -> r.items().isEmpty() && r.commands().isEmpty() ? "it came up empty" : "full";
            case PARTIAL -> "partly delivered" + (r.message() == null ? "" : " (" + r.message() + ")");
            case NONE -> "not delivered" + (r.message() == null ? "" : " (" + r.message() + ")");
        };
    }

    static String count(int n, String thing) {
        String plural = thing.endsWith("y") ? thing.substring(0, thing.length() - 1) + "ies" : thing + "s";
        return n + " " + (n == 1 ? thing : plural);
    }

    private static String cap(Tab t) {
        String n = t.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
