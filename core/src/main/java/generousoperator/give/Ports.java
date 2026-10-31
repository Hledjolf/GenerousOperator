package generousoperator.give;

import generousoperator.api.Person;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * What the core needs from the game and from CommandDeck. The Hytale adapter implements each one; tests use
 * fakes. Nothing here names a game or CommandDeck type.
 */
public final class Ports {
    private Ports() {
    }

    /** Permission nodes. Operators pass every node (the adapter's {@link Players#hasPermission} decides). */
    public static final String GIVE = "generousoperator.give";
    public static final String EDIT = "generousoperator.edit";

    public interface Players {
        /** The online player with this id, or null if they aren't online. */
        Person online(String id);

        List<Person> everyoneOnline();

        boolean hasPermission(String id, String node);

        /** A chat line to one player. */
        void tell(String id, String text);

        /** A chat line to everyone online. */
        void announce(String text);
    }

    /** CommandDeck, seen from the core. {@code available()} is false without CommandDeck 1.1. */
    public interface Runner {
        boolean available();

        /** What to run: a preset id, or command lines written as a person would type them ({@code {target}} filled by CommandDeck). */
        record Plan(String presetId, List<String> lines) {
            public Plan {
                lines = lines == null ? null : List.copyOf(lines);
            }

            public static Plan preset(String id) {
                return new Plan(id, null);
            }

            public static Plan lines(List<String> lines) {
                return new Plan(null, lines);
            }
        }

        /** CommandDeck's check, flattened. {@code code} and {@code message} are CommandDeck's refusal when not ok. */
        record Check(boolean ok, String code, String message, String detail, List<String> preview,
                     boolean needsConfirmation, String confirmPrompt, String token) {
            public Check {
                preview = List.copyOf(preview);
            }
        }

        record Started(boolean ok, String code, String message, String detail) {
        }

        /**
         * How a run ended.
         *
         * @param completed every step ran
         * @param sent the plan's lines the game accepted and ran, in order, as written in the plan ({@code {target}} not filled in)
         */
        record Ran(boolean completed, List<String> sent, String reason, String message) {
            public Ran {
                sent = List.copyOf(sent);
            }
        }

        /** A CommandDeck preset in category {@code Gifts}. {@code mod} is the sidecar's mod id. */
        record Preset(String id, String name, String description, String mod) {
        }

        /** Every preset in category {@code Gifts}, whoever may run it. */
        List<Preset> giftPresets();

        Check check(Person giver, Person recipient, Plan plan, String source);

        /** Starts the run; {@code done} is called exactly once, unless this returns not ok. */
        Started start(Person giver, Person recipient, Plan plan, String source, String token, Consumer<Ran> done);
    }

    /** The live item registry, modded items included. */
    public interface Items {
        boolean exists(String itemId);

        /** The item's display name, or its id if it has none. */
        String name(String itemId);
    }

    /** Puts items straight into a player's inventory, for server presents. No commands. */
    public interface Inventory {
        /**
         * @param given what fit, by item id
         * @param problem why some or all of it didn't fit, or null
         */
        record Delivered(Map<String, Long> given, String problem) {
            public Delivered {
                given = Map.copyOf(given);
            }
        }

        /** Gives the items, on the game's own thread; {@code done} is called exactly once. */
        void give(String recipientId, Map<String, Long> items, Consumer<Delivered> done);
    }

    /** Calendar &amp; Seasons, when installed: is this occasion on today? */
    public interface Occasions {
        boolean isOn(String occasionId);

        Occasions NONE = id -> false;
    }

    /** The shared power curve. Until it exists, every item is unrated. */
    public interface PowerCurve {
        /** The power of {@code count} of this item, or null if it isn't rated (modded items always). */
        Integer power(String itemId, long count);

        PowerCurve NONE = (id, count) -> null;
    }
}
