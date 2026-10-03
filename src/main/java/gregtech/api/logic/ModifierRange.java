package gregtech.api.logic;

import javax.annotation.Nonnull;

/** The values a machine takes for one {@link ModifierKind}, both ends included. */
public record ModifierRange(@Nonnull ModifierKind kind, long min, long max) {

    public ModifierRange {
        if (min > max) throw new IllegalArgumentException(kind + ": min " + min + " > max " + max);
    }

    public boolean contains(long value) {
        return value >= min && value <= max;
    }
}
