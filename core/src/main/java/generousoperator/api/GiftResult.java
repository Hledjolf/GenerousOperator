package generousoperator.api;

import java.util.List;
import java.util.Map;

/**
 * How a gift ended.
 *
 * @param delivery FULL, PARTIAL (some of it reached the recipient: it still counts toward the limits) or NONE
 * @param items what was given, by item id, for an item list (empty for a command preset)
 * @param commands the command lines that were sent, as a person (empty from the server)
 * @param message for people: why it ended short, or null when delivered in full
 */
public record GiftResult(String giftId, String kit, String recipientId, Delivery delivery, Map<String, Long> items,
                         List<String> commands, String message) {
    public enum Delivery { FULL, PARTIAL, NONE }

    public GiftResult {
        items = Map.copyOf(items);
        commands = List.copyOf(commands);
    }
}
