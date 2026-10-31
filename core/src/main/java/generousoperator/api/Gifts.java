package generousoperator.api;

import java.util.List;

/**
 * Generous Operator's public API: give a kit from the catalog to a player. Generous Operator's own window and
 * command use this same call, so every gift (pressed, typed or asked for by a mod) goes through the same
 * permission checks, limits, once-per-player rule, recipient message and log line.
 *
 * <p>Get it with {@link GenerousOperatorApi#get()}, and check {@link #apiVersion()} starts with {@code 1.}.
 * Declare Generous Operator as an <em>optional</em> dependency and carry on without gifts if it's missing.
 *
 * <p>Two ways to give (see {@link GiveRequest#giver()}):
 * <ul>
 *   <li><b>As a person:</b> the kit runs through CommandDeck as that person, with their permissions. Any kit form.</li>
 *   <li><b>From the server</b> (giver null): item-list kits only, put straight into the recipient's inventory,
 *       and only where the server operator's trust rules allow the calling mod to give that kit.</li>
 * </ul>
 *
 * <p>Every method is safe to call from any thread. Listener callbacks arrive on the game's thread and must
 * return quickly.
 */
public interface Gifts {
    String API_VERSION = "1.0";

    String apiVersion();

    /** Every giftable kit in the catalog, in load order. */
    List<KitInfo> kits();

    /**
     * Checks a gift without giving anything: which kit would be given (for a list of seasonal variants), whether
     * it's allowed, and a preview. If {@link GiveCheck#needsConfirmation()}, show the person
     * {@link GiveCheck#confirmPrompt()} and pass {@link GiveCheck#confirmToken()} to {@link #give}.
     */
    GiveCheck check(GiveRequest request);

    /**
     * Gives the kit. Re-runs every check. Returns at once; {@code listener} (may be null) hears exactly once,
     * when the gift has been delivered or has failed.
     *
     * @param confirmToken from {@link #check}, when it asked for confirmation; otherwise null
     */
    GiveStart give(GiveRequest request, String confirmToken, GiveListener listener);
}
