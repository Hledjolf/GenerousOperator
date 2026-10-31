package generousoperator.catalog;

/**
 * A problem found while loading or rolling a catalog, for the server log. Loading never fails as a whole: an
 * {@code ERROR} skips one entry (or one file, if the file itself is unreadable), a {@code WARNING} skips one
 * reference or field and keeps the entry.
 *
 * @param where the file or source, then a JSON path inside it, such as {@code Hledjolf:CalendarSeasons kits[3].rolls[0]}
 * @param code a stable code, such as {@code unknown_item} or {@code cycle}
 */
public record Issue(Level level, String where, String code, String message) {
    public enum Level { WARNING, ERROR }

    @Override
    public String toString() {
        return level + " " + where + ": " + message + " (" + code + ")";
    }
}
