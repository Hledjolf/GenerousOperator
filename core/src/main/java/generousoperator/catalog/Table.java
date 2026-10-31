package generousoperator.catalog;

import java.util.List;

/**
 * An item list, which may roll its contents (D&amp;D Treasure Type style). A plain item list is a table whose
 * lines all have a 100% chance.
 */
public final class Table {
    private Table() {
    }

    /** Items, a reference to another table, or both. {@code tableRef} is qualified ({@code Group:Name/id}) or null. */
    public record Outcome(List<ItemCount> items, String tableRef) {
        public Outcome {
            items = List.copyOf(items);
        }
    }

    public record ItemCount(String itemId, Dice count) {
    }

    /** One weighted choice of a pick line. */
    public record Choice(long weight, Outcome outcome) {
    }

    /** A line of a table: a chance line or a pick line. */
    public sealed interface Line permits ChanceLine, PickLine {
    }

    /** Happens with {@code percent} percent chance (100 for a plain list). */
    public record ChanceLine(double percent, Outcome outcome) implements Line {
    }

    /** Picks {@code count} choices by weight; without {@code repeat}, each choice comes up at most once. */
    public record PickLine(Dice count, boolean repeat, List<Choice> from) implements Line {
        public PickLine {
            from = List.copyOf(from);
        }
    }
}
