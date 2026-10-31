package generousoperator.api;

/** The result of {@link Gifts#give}: started (with the gift's id, as logged) or refused. */
public record GiveStart(boolean started, String giftId, Refusal refusal) {
}
