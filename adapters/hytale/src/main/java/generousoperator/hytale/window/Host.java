package generousoperator.hytale.window;

import generousoperator.api.Person;
import generousoperator.give.GiftLog;
import generousoperator.give.GiftService;
import generousoperator.give.Ports;
import java.io.IOException;
import java.time.ZoneId;
import java.util.List;

/** What one player's window needs from the plugin. */
public interface Host {
    /** The player the window belongs to: every gift from it is given as them. */
    Person giver();

    GiftService service();

    Ports.Players players();

    /** An item's English name, or its id. */
    String itemName(String itemId);

    /** The gift log, oldest first; {@code skipped[0]} counts damaged lines. */
    List<GiftLog.Line> readLog(int[] skipped) throws IOException;

    /** The server's time zone: days in the log start at its midnight, as the limits do. */
    ZoneId zone();

    /** The command the preview lines name ({@code generousoperator}). */
    String command();
}
