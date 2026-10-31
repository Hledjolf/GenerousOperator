package generousoperator.give;

import generousoperator.api.Gifts;
import generousoperator.api.GiveCheck;
import generousoperator.api.GiveListener;
import generousoperator.api.GiveRequest;
import generousoperator.api.GiveStart;
import generousoperator.api.KitInfo;
import generousoperator.api.Refusal;
import java.util.List;
import java.util.function.Supplier;

/**
 * The {@link Gifts} other mods get. A request's {@code source} is what server-present trust rules match, so it
 * mustn't be whatever the caller says: the game adapter works out which mod made the call, and that becomes the
 * source. A request that names a different mod is refused, so a copy-paste mistake shows up at once instead of
 * borrowing another mod's trust.
 *
 * <p>This stops one mod passing itself off as another through the API. It can't stop code that deliberately
 * goes around the API (reflection into the service), which nothing inside one Java process can.
 */
public final class CheckedGifts implements Gifts {
    /** The source when the adapter can't tell which mod called. No trust rule can name it. */
    public static final String UNIDENTIFIED = "unidentified";

    private final Gifts inner;
    private final Supplier<String> caller;

    /**
     * @param caller the calling mod's id, or null if it can't be told; called on the caller's own thread, from
     *     inside {@link #check} and {@link #give}
     */
    public CheckedGifts(Gifts inner, Supplier<String> caller) {
        this.inner = inner;
        this.caller = caller;
    }

    @Override
    public String apiVersion() {
        return inner.apiVersion();
    }

    @Override
    public List<KitInfo> kits() {
        return inner.kits();
    }

    @Override
    public GiveCheck check(GiveRequest request) {
        Object r = verified(request);
        if (r instanceof Refusal no) return new GiveCheck(false, no, null, List.of(), false, null, null);
        return inner.check((GiveRequest) r);
    }

    @Override
    public GiveStart give(GiveRequest request, String confirmToken, GiveListener listener) {
        Object r = verified(request);
        if (r instanceof Refusal no) return new GiveStart(false, null, no);
        return inner.give((GiveRequest) r, confirmToken, listener);
    }

    /** The request with its source set to the real caller, or a refusal. */
    private Object verified(GiveRequest r) {
        if (r == null) return new Refusal("invalid", "A gift needs a request.", null);
        String who = caller.get();
        if (who == null) who = UNIDENTIFIED;
        if (r.source() != null && !r.source().equals(who)) {
            return new Refusal("source_mismatch", "The request says it comes from " + r.source() + ", but " + who + " made the call.",
                    "declared=" + r.source() + " caller=" + who);
        }
        return new GiveRequest(r.kits(), r.recipientId(), r.giver(), r.reason(), who);
    }
}
