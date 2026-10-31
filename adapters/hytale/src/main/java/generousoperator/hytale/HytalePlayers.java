package generousoperator.hytale;

import com.hypixel.hytale.server.core.Constants;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import generousoperator.api.Person;
import generousoperator.give.Ports;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Players as the core sees them: a person is a player by UUID, or the server console (its own UUID), which may
 * give gifts but never receives one. The console and a singleplayer world's owner pass every permission;
 * operators pass through the permission module's {@code *}.
 */
final class HytalePlayers implements Ports.Players {
    static final String CONSOLE_ID = ConsoleSender.INSTANCE.getUuid().toString();

    static boolean isConsole(String id) {
        return CONSOLE_ID.equals(id);
    }

    static Person console() {
        return new Person(CONSOLE_ID, ConsoleSender.INSTANCE.getUsername());
    }

    static PlayerRef player(String id) {
        UUID u = uuid(id);
        Universe universe = Universe.get();
        return u == null || isConsole(id) || universe == null ? null : universe.getPlayer(u);
    }

    /** An online player by name (exact, ignoring case), or null. */
    static PlayerRef byName(String name) {
        Universe universe = Universe.get();
        return universe == null ? null : universe.getPlayerByUsername(name, NameMatching.EXACT_IGNORE_CASE);
    }

    @Override
    public Person online(String id) {
        PlayerRef p = player(id);
        return p == null ? null : new Person(p.getUuid().toString(), p.getUsername());
    }

    @Override
    public List<Person> everyoneOnline() {
        List<Person> out = new ArrayList<>();
        Universe universe = Universe.get();
        if (universe != null) {
            for (PlayerRef p : universe.getPlayers()) out.add(new Person(p.getUuid().toString(), p.getUsername()));
        }
        out.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()));
        return out;
    }

    @Override
    public boolean hasPermission(String id, String node) {
        if (isConsole(id)) return true;
        UUID u = uuid(id);
        if (u == null) return false;
        if (Constants.SINGLEPLAYER && SingleplayerModule.isOwner(u)) return true;
        PermissionsModule perms = PermissionsModule.get();
        return perms != null && perms.hasPermission(u, node);
    }

    @Override
    public void tell(String id, String text) {
        if (isConsole(id)) {
            ConsoleSender.INSTANCE.sendMessage(Message.raw(text));
            return;
        }
        PlayerRef p = player(id);
        if (p != null) p.sendMessage(Message.raw(text));
    }

    @Override
    public void announce(String text) {
        Universe universe = Universe.get();
        if (universe != null) universe.sendMessage(Message.raw(text));
    }

    private static UUID uuid(String id) {
        if (id == null) return null;
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
