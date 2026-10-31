package generousoperator.hytale;

import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import generousoperator.api.GenerousOperatorApi;
import generousoperator.api.Gifts;
import generousoperator.api.Person;
import generousoperator.give.CheckedGifts;
import generousoperator.give.GiftHistory;
import generousoperator.give.GiftLog;
import generousoperator.give.GiftService;
import generousoperator.give.Ports;
import generousoperator.give.ZoneTable;
import generousoperator.hytale.window.GiftWindow;
import generousoperator.hytale.window.Host;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Generous Operator for Hytale: one-click gifts for server operators, built on CommandDeck. Wires the Hytale side
 * (players, the item registry, inventories, CommandDeck) to the game-agnostic gift service. The API is published
 * at setup; the catalogs are read at boot, after CommandDeck has read its presets, and again on a reload.
 */
public final class GenerousOperatorPlugin extends JavaPlugin {
    private static volatile GenerousOperatorPlugin instance;

    private final Settings settings = new Settings();
    private HytalePlayers players;
    private HytaleItems items;
    private GiftLog giftLog;
    private GiftService service;
    private Gifts api;
    /** Each player's window, kept while the server runs, so a batch and the open groups survive closing it. */
    private final Map<String, GiftWindow> windows = new ConcurrentHashMap<>();

    public GenerousOperatorPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        Path data = getDataDirectory();
        String problem = settings.load(data.resolve("config.json"));
        if (problem != null) warn(problem);

        Consumer<String> serverLog = this::warn;
        players = new HytalePlayers();
        items = new HytaleItems(serverLog);
        giftLog = new GiftLog(data.resolve("gifts.log"));
        GiftHistory history = new GiftHistory();
        int[] skipped = {0};
        try {
            history.load(giftLog.read(skipped));
        } catch (IOException e) {
            warn("Can't read " + giftLog.file() + ", so today's limits start from zero: " + e.getMessage());
        }
        if (skipped[0] > 0) warn(skipped[0] + " damaged lines in gifts.log were skipped.");

        service = new GiftService(players, new DeckRunner(), items, items, Clock.systemDefaultZone(), giftLog, history, serverLog);
        service.rules(settings.rules());
        service.power(powerCurve());
        api = new CheckedGifts(service, new Callers(getClass().getClassLoader()));
        if (!GenerousOperatorApi.publish(api)) {
            getLogger().atSevere().log("[GenerousOperator] Another mod has already published a Generous Operator API; other mods can't give gifts.");
        }
        instance = this;

        PermissionsModule.registerPermission(Ports.GIVE);
        PermissionsModule.registerPermission(Ports.EDIT);
        getCommandRegistry().registerCommand(new GiftCommand(this, GiftCommand.NAME));
        // After CommandDeck, which reads its presets at boot too.
        getEventRegistry().register(EventPriority.LATE, BootEvent.class, e -> boot());
    }

    private synchronized void boot() {
        String loaded = load();
        boolean alias = !commandTaken(GiftCommand.ALIAS);
        if (alias) getCommandRegistry().registerCommand(new GiftCommand(this, GiftCommand.ALIAS));
        info("ready (API " + Gifts.API_VERSION + "): " + loaded + (alias ? " /gift is an alias." : " /gift belongs to another mod, so use /generousoperator."));
    }

    @Override
    protected synchronized void shutdown() {
        GenerousOperatorApi.withdraw(api);
        if (instance == this) instance = null;
    }

    /** {@code /generousoperator reload}: settings and catalogs. */
    synchronized String reload() {
        String problem = settings.load(getDataDirectory().resolve("config.json"));
        if (problem != null) warn(problem);
        service.rules(settings.rules());
        String loaded = load();
        info("reloaded: " + loaded);
        return "Reloaded: " + loaded + (problem == null ? "" : " Settings: " + problem);
    }

    /**
     * Mod Settings Manager's live-apply hook: {@code config.json} is already written when this runs. Every
     * setting applies to the next gift; a new nesting depth rebuilds the catalog.
     */
    public boolean onSettingsChanged(Map<String, Object> changed) {
        String problem = settings.load(getDataDirectory().resolve("config.json"));
        if (problem != null) warn(problem);
        int depth = service.rules().maxDepth();
        service.rules(settings.rules());
        if (settings.rules().maxDepth() != depth) load();
        info("settings applied: " + String.join(", ", changed.keySet()));
        return true;
    }

    private String load() {
        HytaleCatalogs.Loaded l = HytaleCatalogs.load(getDataDirectory(), items::exists, settings.rules().maxDepth(), this::info, this::warn);
        service.catalog(l.catalog());
        SeasonsOccasions seasons = SeasonsOccasions.find();
        service.occasions(seasons);
        int kits = service.catalog().kits().size(), presets = kits - l.catalog().kits().size();
        return kits + " kits (" + presets + " from CommandDeck presets), " + l.files() + " catalogs, " + l.issues().size() + " problems"
                + (new DeckRunner().available() ? "" : "; CommandDeck 1.1 isn't installed, so only server presents can be given")
                + (seasons == null ? "" : "; Calendar & Seasons occasions on") + ".";
    }

    /** The zone table the build put in the jar; without it (or with a layout this version can't read), nothing is rated. */
    private Ports.PowerCurve powerCurve() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("generousoperator/power-zones.tsv")) {
            if (in == null) {
                warn("This build has no power curve, so every kit is unrated as a server present.");
                return Ports.PowerCurve.NONE;
            }
            ZoneTable t = ZoneTable.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (t == null) {
                warn("The power curve in this build has a layout this version can't read, so every kit is unrated.");
                return Ports.PowerCurve.NONE;
            }
            info("power curve: " + t.size() + " items rated (" + t.origin() + ").");
            return t;
        } catch (IOException e) {
            warn("Can't read the power curve, so every kit is unrated: " + e.getMessage());
            return Ports.PowerCurve.NONE;
        }
    }

    private static boolean commandTaken(String name) {
        for (Map.Entry<String, AbstractCommand> e : CommandManager.get().getCommandRegistration().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) || e.getValue().getName().equalsIgnoreCase(name)) return true;
            for (String a : e.getValue().getAliases()) if (a.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private void info(String s) {
        getLogger().atInfo().log("[GenerousOperator] %s", s);
    }

    private void warn(String s) {
        getLogger().atWarning().log("[GenerousOperator] %s", s);
    }

    /** The running plugin, or null before setup. For the headless scenarios. */
    public static GenerousOperatorPlugin instance() {
        return instance;
    }

    /** The service itself, unchecked: Generous Operator's own command and window, and the headless scenarios. */
    public GiftService service() {
        return service;
    }

    Ports.Players players() {
        return players;
    }

    HytaleItems items() {
        return items;
    }

    GiftLog giftLog() {
        return giftLog;
    }

    /** The player's window, made on first use. */
    public GiftWindow windowFor(Person giver) {
        return windows.computeIfAbsent(giver.id(), id -> new GiftWindow(new Host() {
            @Override public Person giver() { return players.online(id) != null ? players.online(id) : giver; }
            @Override public GiftService service() { return service; }
            @Override public Ports.Players players() { return players; }
            @Override public String itemName(String itemId) { return items.name(itemId); }
            @Override public List<GiftLog.Line> readLog(int[] skipped) throws IOException { return giftLog.read(skipped); }
            @Override public ZoneId zone() { return ZoneId.systemDefault(); }
            @Override public String command() { return GiftCommand.NAME; }
        }));
    }
}
