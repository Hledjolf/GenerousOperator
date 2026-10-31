package generousoperator.api;

import java.util.List;

/**
 * One gift.
 *
 * @param kits a kit id (qualified {@code <Group:Name>/<id>}, or bare if only one catalog has it), or a list of
 *     seasonal variants: the first whose occasion is on today is given, otherwise the last one
 * @param recipientId the recipient's stable player id (on Hytale, the UUID as a string)
 * @param giver the person giving, whose permissions the gift runs with; null to give from the server
 * @param reason why, for the log ("completed plan calendar-rollover"); may be null
 * @param source who asked: the calling mod's id ({@code Hledjolf:PlaytestGamified}), or null. Generous
 *     Operator works out which mod made the call itself and uses that; a source naming a different mod is
 *     refused ({@code source_mismatch}). Server-present trust rules are matched against it, and it's logged.
 */
public record GiveRequest(List<String> kits, String recipientId, Person giver, String reason, String source) {
    public GiveRequest {
        kits = List.copyOf(kits);
    }

    public static GiveRequest asPerson(String kit, String recipientId, Person giver, String reason, String source) {
        return new GiveRequest(List.of(kit), recipientId, giver, reason, source);
    }

    public static GiveRequest fromServer(String kit, String recipientId, String reason, String source) {
        return new GiveRequest(List.of(kit), recipientId, null, reason, source);
    }

    public boolean fromServer() {
        return giver == null;
    }
}
