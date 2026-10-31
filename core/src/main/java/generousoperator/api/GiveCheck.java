package generousoperator.api;

import java.util.List;

/**
 * The result of {@link Gifts#check}.
 *
 * @param kit the qualified id of the kit that would be given (for seasonal variants, the one chosen); null if
 *     no kit resolved
 * @param preview what will be given: for a command preset, its command lines; for an item list, one line per
 *     item with its range ("2-12 × Ingredient_Bar_Copper"), "may contain" when the kit rolls
 * @param confirmToken pass to {@link Gifts#give}; null when no confirmation is needed. Expires after 60 seconds.
 */
public record GiveCheck(boolean ok, Refusal refusal, String kit, List<String> preview, boolean needsConfirmation,
                        String confirmPrompt, String confirmToken) {
    public GiveCheck {
        preview = List.copyOf(preview);
    }
}
