package net.prason.xaeronav.transit;

/** MTRの応答が期待した形でない。古いMTRや、別のものが同じポートで答えているとき。 */
public final class TransitFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TransitFormatException(String message) {
        super(message);
    }
}
