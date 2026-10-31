package generousoperator.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import generousoperator.catalog.Catalog;
import generousoperator.catalog.CatalogParser;
import generousoperator.catalog.Issue;
import generousoperator.catalog.Kit;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Finds the catalogs: {@code generousoperator/catalog.json} inside each loaded mod's jar (or unpacked folder),
 * whose kits take that mod's id as their source, then the server's own {@code catalogs/*.json} in the data
 * folder (source {@code server}). A file that doesn't read is logged and left out; it never stops the others.
 */
final class HytaleCatalogs {
    static final String ENTRY = "generousoperator/catalog.json";

    /** What was loaded, for the boot line. */
    record Loaded(Catalog catalog, int files, List<Issue> issues) {
    }

    private HytaleCatalogs() {
    }

    /** {@code log} hears what was read; {@code warn} hears the problems. */
    static Loaded load(Path dataDir, Predicate<String> knownItem, int maxDepth, Consumer<String> log, Consumer<String> warn) {
        List<Issue> issues = new ArrayList<>();
        List<Kit> parsed = new ArrayList<>();
        int files = 0;
        for (PluginBase p : PluginManager.get().getPlugins()) {
            if (!(p instanceof JavaPlugin jp) || jp.getFile() == null) continue;
            String where = jp.getFile().getFileName() + "!/" + ENTRY;
            try {
                String text = readBundled(jp.getFile(), ENTRY);
                if (text == null) continue;
                files++;
                List<Issue> mine = new ArrayList<>();
                List<Kit> kits = CatalogParser.parseMod(text, p.getIdentifier().toString(), mine);
                parsed.addAll(kits);
                issues.addAll(mine);
                log.accept("Catalog " + where + " for " + p.getIdentifier() + ": " + kits.size() + " entries" + (mine.isEmpty() ? "" : ", " + mine.size() + " problems"));
            } catch (IOException e) {
                warn.accept("Can't read " + where + ": " + e.getMessage());
            }
        }
        Path dir = dataDir.resolve("catalogs");
        if (Files.isDirectory(dir)) {
            List<Path> found = new ArrayList<>();
            try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "*.json")) {
                for (Path f : s) found.add(f);
            } catch (IOException e) {
                warn.accept("Can't list " + dir + ": " + e.getMessage());
            }
            found.sort(null);
            for (Path f : found) {
                try {
                    files++;
                    List<Issue> mine = new ArrayList<>();
                    List<Kit> kits = CatalogParser.parseServer(Files.readString(f, StandardCharsets.UTF_8), "catalogs/" + f.getFileName(), mine);
                    parsed.addAll(kits);
                    issues.addAll(mine);
                    log.accept("Catalog catalogs/" + f.getFileName() + ": " + kits.size() + " entries" + (mine.isEmpty() ? "" : ", " + mine.size() + " problems"));
                } catch (IOException e) {
                    warn.accept("Can't read " + f + ": " + e.getMessage());
                }
            }
        }
        Catalog c = Catalog.build(parsed, knownItem, maxDepth, issues);
        for (Issue i : issues) warn.accept(i.toString());
        return new Loaded(c, files, List.copyOf(issues));
    }

    /** A text file from a plugin jar or an unpacked plugin folder, or null. */
    static String readBundled(Path jarOrDir, String entry) throws IOException {
        if (Files.isDirectory(jarOrDir)) {
            Path f = jarOrDir.resolve(entry);
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        }
        if (!Files.isRegularFile(jarOrDir)) return null;
        try (ZipFile zf = new ZipFile(jarOrDir.toFile())) {
            ZipEntry e = zf.getEntry(entry);
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
