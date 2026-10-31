package generousoperator.give;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What has been given, for the limits and the once-per-player rule. Loaded from {@code gifts.log} at
 * startup, then kept up to date as gifts finish.
 *
 * <p>A gift counts once anything of it reached the recipient (delivery full or partial). While a gift is
 * being delivered it's <em>pending</em>: it already counts, so two gifts can't both slip under a limit, and
 * it's dropped again if nothing was delivered.
 */
public final class GiftHistory {
    private record Entry(String giftId, Instant time, String giverId, String recipientId, String kit, boolean limits) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> pending = new HashMap<>();
    private final Set<String> received = new HashSet<>();   // recipientId + "\t" + kit

    public synchronized void load(List<GiftLog.Line> lines) {
        for (GiftLog.Line l : lines) {
            if ("none".equals(l.delivery())) continue;
            add(new Entry(l.giftId(), l.time(), l.giverId(), l.recipientId(), l.kit(), l.limits()));
        }
    }

    /** Counts a gift from now on, before it's delivered. */
    public synchronized void begin(String giftId, Instant time, String giverId, String recipientId, String kit, boolean limits) {
        Entry e = new Entry(giftId, time, giverId, recipientId, kit, limits);
        pending.put(giftId, e);
        add(e);
    }

    /** Keeps a pending gift if anything was delivered, and drops it otherwise. */
    public synchronized void finish(String giftId, boolean delivered) {
        Entry e = pending.remove(giftId);
        if (e == null || delivered) return;
        entries.remove(e);
        received.remove(e.recipientId() + "\t" + e.kit());
        for (Entry other : entries) if (other.recipientId().equals(e.recipientId()) && other.kit().equals(e.kit())) received.add(other.recipientId() + "\t" + other.kit());
    }

    public synchronized boolean hasReceived(String recipientId, String kit) {
        return received.contains(recipientId + "\t" + kit);
    }

    /** Gifts this giver gave on the local day of {@code now}, counting only kits under the limits. */
    public synchronized int givenOn(String giverId, Instant now, ZoneId zone) {
        LocalDate day = now.atZone(zone).toLocalDate();
        int n = 0;
        for (Entry e : entries) if (e.limits() && giverId.equals(e.giverId()) && e.time().atZone(zone).toLocalDate().equals(day)) n++;
        return n;
    }

    /** Gifts this player received on the local day of {@code now}, counting only kits under the limits. */
    public synchronized int receivedOn(String recipientId, Instant now, ZoneId zone) {
        LocalDate day = now.atZone(zone).toLocalDate();
        int n = 0;
        for (Entry e : entries) if (e.limits() && recipientId.equals(e.recipientId()) && e.time().atZone(zone).toLocalDate().equals(day)) n++;
        return n;
    }

    /** When this giver last gave this recipient a kit under the limits, or null. */
    public synchronized Instant lastBetween(String giverId, String recipientId) {
        Instant last = null;
        for (Entry e : entries) {
            if (!e.limits() || !giverId.equals(e.giverId()) || !recipientId.equals(e.recipientId())) continue;
            if (last == null || e.time().isAfter(last)) last = e.time();
        }
        return last;
    }

    private void add(Entry e) {
        entries.add(e);
        received.add(e.recipientId() + "\t" + e.kit());
    }
}
