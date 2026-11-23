package gregtech.api.logic;

import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nonnull;

import gregtech.api.util.GTUtility;

/**
 * A {@code Formula} computes one number of a {@link ProcessingSpec}. Each record type except {@link Custom} has one
 * shape of tooltip line.
 */
public sealed interface Formula {

    double apply(@Nonnull ProcessingInputs inputs);

    @Nonnull
    Set<ModifierKind> modifierKinds();

    record Constant(double value) implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return value;
        }

        @Nonnull
        @Override
        public Set<ModifierKind> modifierKinds() {
            return Set.of();
        }
    }

    record Supplied(@Nonnull DoubleSupplier value) implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return value.getAsDouble();
        }

        @Nonnull
        @Override
        public Set<ModifierKind> modifierKinds() {
            return Set.of();
        }
    }

    /** {@code base + perTier * tier}. The tier is the value plus the kind's tier offset. */
    record PerTier(double base, double perTier, @Nonnull ModifierKind.IntKind kind) implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return base + perTier * (kind.countedTier(inputs.value(kind)));
        }

        @Nonnull
        @Override
        public Set<ModifierKind> modifierKinds() {
            return Set.of(kind);
        }
    }

    /** {@code base} at the first tier, times {@code factor} per further tier. */
    record CompoundPerTier(double base, double factor, @Nonnull ModifierKind.IntKind kind) implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return base * GTUtility.powInt(factor, kind.countedTier(inputs.value(kind)) - 1);
        }

        @Nonnull
        @Override
        public Set<ModifierKind> modifierKinds() {
            return Set.of(kind);
        }
    }

    /** {@code perTier} per voltage tier, counting ULV as LV. */
    record PerVoltageTier(int perTier) implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return perTier * Math.max(1, inputs.voltageTier());
        }

        @Nonnull
        @Override
        public Set<ModifierKind> modifierKinds() {
            return Set.of(ModifierKind.VOLTAGE);
        }
    }

    /** From {@code min} to {@code max} as {@code kind} rises from 0 to the max of {@code kind}'s range. */
    record Rising(double min, double max, @Nonnull ModifierKind.IntKind kind) implements Formula {

        public Rising {
            kind.requireRange();
        }

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            double rise = (max - min) * inputs.value(kind)
                / kind.requireRange()
                    .max();
            return min + rise;
        }

        @Nonnull
        @Override
        public Set<ModifierKind> modifierKinds() {
            return Set.of(kind);
        }
    }

    /**
     * From {@code min} to {@code max} per voltage tier as {@code kind} rises from 0 to the max of {@code kind}'s range.
     */
    record RisingPerVoltageTier(int min, int max, @Nonnull ModifierKind.IntKind kind) implements Formula {

        public RisingPerVoltageTier {
            kind.requireRange();
        }

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            double rise = (max - min) * inputs.value(kind)
                / (double) kind.requireRange()
                    .max();
            return (int) ((min + rise) * inputs.voltageTier());
        }

        @Nonnull
        @Override
        public Set<ModifierKind> modifierKinds() {
            return Set.of(kind, ModifierKind.VOLTAGE);
        }
    }

    /**
     * {@link ProcessingSpec.Builder} creates a {@code Custom} formula from each function of the
     * {@link ProcessingInputs} that a builder method takes. {@link ProcessingSpec} doesn't write a tooltip line for a
     * {@code Custom} formula. Describe the formula with {@link ProcessingSpec.Builder#customTooltip}.
     */
    record Custom(@Nonnull ToDoubleFunction<ProcessingInputs> function, @Nonnull Set<ModifierKind> modifierKinds)
        implements Formula {

        public Custom {
            modifierKinds = Set.copyOf(modifierKinds);
        }

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return function.applyAsDouble(inputs);
        }
    }
}
