package generousoperator.api;

/** Hears how a gift ended. Called exactly once, on the game's thread. */
public interface GiveListener {
    void onFinish(GiftResult result);
}
