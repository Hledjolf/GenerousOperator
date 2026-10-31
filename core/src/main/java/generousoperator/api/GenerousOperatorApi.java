package generousoperator.api;

/**
 * Where other mods get the API. Null until Generous Operator has set up. Only one instance can be published at
 * a time, so another mod can't quietly replace Generous Operator's with its own.
 */
public final class GenerousOperatorApi {
    private static Gifts instance;

    private GenerousOperatorApi() {
    }

    public static synchronized Gifts get() {
        return instance;
    }

    /**
     * Called by Generous Operator's plugin at setup. False, and nothing changes, if an instance is already
     * published.
     */
    public static synchronized boolean publish(Gifts gifts) {
        if (gifts == null || instance != null) return false;
        instance = gifts;
        return true;
    }

    /** Called at shutdown: clears the published instance, but only if it is this one. */
    public static synchronized void withdraw(Gifts gifts) {
        if (instance == gifts) instance = null;
    }
}
