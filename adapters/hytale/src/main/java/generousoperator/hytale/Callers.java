package generousoperator.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginClassLoader;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Which mod is calling the API. Hytale loads each mod's jar with its own {@link PluginClassLoader}, so the first
 * class on the call stack that isn't Generous Operator's or the JDK's names the caller: its class loader is one
 * mod's. That class loader is matched to the plugin that owns it, and the plugin's {@code Group:Name} is the
 * answer. Null when no frame belongs to a mod's loader (the server itself), or when a loader is shared, so it
 * can't say which mod it is.
 */
final class Callers implements Supplier<String> {
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final ClassLoader own;

    Callers(ClassLoader own) {
        this.own = own;
    }

    @Override
    public String get() {
        Optional<ClassLoader> loader = WALKER.walk(frames -> frames
                .map(f -> f.getDeclaringClass().getClassLoader())
                .filter(l -> l != null && l != own && l instanceof PluginClassLoader)
                .findFirst());
        return loader.map(Callers::owner).orElse(null);
    }

    /** The {@code Group:Name} of the one plugin loaded by {@code loader}, or null. */
    static String owner(ClassLoader loader) {
        String found = null;
        for (PluginBase p : PluginManager.get().getPlugins()) {
            if (!(p instanceof JavaPlugin jp) || jp.getClassLoader() != loader) continue;
            if (found != null) return null;
            found = p.getIdentifier().toString();
        }
        return found;
    }
}
