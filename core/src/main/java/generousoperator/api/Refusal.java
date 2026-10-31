package generousoperator.api;

/**
 * Why a gift was refused. {@code code} is stable; {@code message} is for people and may change; {@code detail}
 * is for logs (never parse it).
 *
 * <table>
 *   <caption>Codes</caption>
 *   <tr><td>{@code invalid}</td><td>The request itself is malformed (no kits, no recipient, source too long)</td></tr>
 *   <tr><td>{@code source_mismatch}</td><td>The request names a source other than the mod that made the call</td></tr>
 *   <tr><td>{@code unknown_kit}</td><td>A kit id isn't in the catalog, or a bare id is in several catalogs</td></tr>
 *   <tr><td>{@code recipient_offline}</td><td>The recipient isn't online</td></tr>
 *   <tr><td>{@code no_permission}</td><td>The giver lacks {@code generousoperator.give}</td></tr>
 *   <tr><td>{@code once_per_player}</td><td>The recipient already has this once-per-player kit</td></tr>
 *   <tr><td>{@code giver_daily_cap}, {@code recipient_daily_cap}</td><td>A daily limit is reached</td></tr>
 *   <tr><td>{@code cooldown}</td><td>This giver gave this recipient a gift too recently</td></tr>
 *   <tr><td>{@code not_trusted}</td><td>No trust rule lets this source give this kit from the server</td></tr>
 *   <tr><td>{@code over_power_cap}</td><td>The kit's best roll is stronger than the trust rule's cap</td></tr>
 *   <tr><td>{@code unrated}</td><td>The kit has items the power curve can't rate, and the rule doesn't approve it</td></tr>
 *   <tr><td>{@code preset_from_server}</td><td>A command preset can only be given as a person</td></tr>
 *   <tr><td>{@code no_commanddeck}</td><td>Giving as a person needs CommandDeck 1.1, which isn't installed</td></tr>
 *   <tr><td>{@code commanddeck}</td><td>CommandDeck refused the run; detail starts with its own code</td></tr>
 *   <tr><td>{@code needs_confirmation}, {@code confirmation_mismatch}</td><td>Confirm with the token from check</td></tr>
 * </table>
 */
public record Refusal(String code, String message, String detail) {
}
