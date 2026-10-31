package generousoperator.api;

/** A player, or the console. {@code id} is the game's stable player id as a string; {@code name} is for messages and the log. */
public record Person(String id, String name) {
}
