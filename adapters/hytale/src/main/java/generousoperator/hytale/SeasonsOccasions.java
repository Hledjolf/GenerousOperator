package generousoperator.hytale;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import generousoperator.give.Ports;
import java.lang.reflect.Method;

/**
 * Calendar &amp; Seasons' occasions, when it's installed: {@code calendarseasons.api.CalendarSeasonsApi.get().isOn(id)}.
 * Found by reflection through that mod's own class loader, so there's no dependency either way. Without it, or if
 * its API doesn't answer, no occasion is on and every seasonal list gives its last (default) kit.
 */
final class SeasonsOccasions implements Ports.Occasions {
    static final String PLUGIN = "Hledjolf:CalendarSeasons";
    private static final String API = "calendarseasons.api.CalendarSeasonsApi";

    private final Method get;

    private SeasonsOccasions(Method get) {
        this.get = get;
    }

    /** The occasions, or null if Calendar &amp; Seasons (or its API) isn't there. */
    static SeasonsOccasions find() {
        for (PluginBase p : PluginManager.get().getPlugins()) {
            if (!(p instanceof JavaPlugin jp) || !PLUGIN.equals(p.getIdentifier().toString())) continue;
            try {
                return new SeasonsOccasions(Class.forName(API, true, jp.getClassLoader()).getMethod("get"));
            } catch (ReflectiveOperationException | LinkageError e) {
                return null;
            }
        }
        return null;
    }

    @Override
    public boolean isOn(String occasionId) {
        try {
            Object api = get.invoke(null);
            if (api == null) return false;
            // Through get()'s declared type: the instance's own class may not be public.
            return Boolean.TRUE.equals(get.getReturnType().getMethod("isOn", String.class).invoke(api, occasionId));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
