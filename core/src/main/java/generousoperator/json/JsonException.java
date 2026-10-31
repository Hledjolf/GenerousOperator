package generousoperator.json;

/** Malformed JSON, with the line and column in the message. */
public final class JsonException extends RuntimeException {
    public JsonException(String message) {
        super(message);
    }
}
