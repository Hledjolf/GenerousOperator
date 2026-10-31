package generousoperator.catalog;

import java.util.List;

/**
 * One catalog entry: a giftable kit, or a table that exists only to be nested ({@code giftable} false).
 *
 * <p>A kit's contents are either a CommandDeck preset ({@code preset}, given as a person only) or an item list
 * ({@code lines}, given as a person or from the server). Exactly one is set.
 *
 * @param source the mod that shipped the catalog ({@code mod.id}), or {@link #SERVER} for the server's own
 *     catalogs. Filled in by the loader, never by the author.
 * @param id the id within its catalog
 * @param assignedMod for a server kit made in the editor for a mod under development: that mod, as a label only.
 */
public record Kit(
        String source,
        String id,
        String name,
        String description,
        boolean giftable,
        String preset,
        List<Table.Line> lines,
        String group,
        String kind,
        Integer tier,
        String occasion,
        String biome,
        List<String> tags,
        boolean limits,
        boolean oncePerPlayer,
        String message,
        String assignedMod) {

    /** The source of kits from the server's own catalogs (the kit editor, or files an operator writes). */
    public static final String SERVER = "server";

    /** The group of a {@code Gifts} preset that no catalog entry describes. */
    public static final String OTHER_GROUP = "Other";

    public Kit {
        lines = lines == null ? null : List.copyOf(lines);
        tags = List.copyOf(tags);
    }

    /** {@code <source>/<id>}: how other catalogs, the log and callers name this kit. */
    public String qualifiedId() {
        return qualify(source, id);
    }

    public boolean isPreset() {
        return preset != null;
    }

    public static String qualify(String source, String id) {
        return source + "/" + id;
    }
}
