package gregtech.api.logic;

import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nonnull;

import gregtech.api.util.GTUtility;

/**
 * One number of a {@link ProcessingSpec} as data: the kinds it reads, and a shape the tooltip line is written from.
 * Speeds are kept as speeds: 2.5 is 250%.
 */
public sealed interface Formula {

    double apply(@Nonnull ProcessingInputs inputs);

    /** The kinds the formula reads. A {@link Custom} formula is given them by its caller. */
    @Nonnull
    Set<ModifierKind> reads();

    record Constant(double value) implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return value;
        }

        @Nonnull
        @Override
        public Set<ModifierKind> reads() {
            return Set.of();
        }
    }

    /** Read on every use, such as a config value. */
    record Supplied(@Nonnull DoubleSupplier value) implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return value.getAsDouble();
        }

        @Nonnull
        @Override
        public Set<ModifierKind> reads() {
            return Set.of();
        }
    }

    /** {@code base + perTier * tier}, where the tier is {@link ModifierKind.IntKind#countedTier}. */
    record PerTier(double base, double perTier, @Nonnull ModifierKind.IntKind kind) implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return base + perTier * (kind.countedTier(inputs.value(kind)));
        }

        @Nonnull
        @Override
        public Set<ModifierKind> reads() {
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
        public Set<ModifierKind> reads() {
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
        public Set<ModifierKind> reads() {
            return Set.of(ModifierKind.VOLTAGE);
        }
    }

    /** From {@code min} to {@code max} as {@code kind} rises from 0 to {@code kindMax}. */
    record Rising(double min, double max, @Nonnull ModifierKind.IntKind kind, int kindMax) implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return min + (max - min) * inputs.value(kind) / kindMax;
        }

        @Nonnull
        @Override
        public Set<ModifierKind> reads() {
            return Set.of(kind);
        }
    }

    /**
     * From {@code min} to {@code max} per voltage tier as {@code kind} rises from 0 to {@code kindMax}. The product is
     * a
     * float, as in the machines' code, so the truncation to whole parallels matches.
     */
    record RisingPerVoltageTier(int min, int max, @Nonnull ModifierKind.IntKind kind, int kindMax) implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return (int) ((min + (max - min) * inputs.value(kind) / (float) kindMax) * inputs.voltageTier());
        }

        @Nonnull
        @Override
        public Set<ModifierKind> reads() {
            return Set.of(kind, ModifierKind.VOLTAGE);
        }
    }

    /**
     * Plain code, with no shape a planner or tooltip can read. Describe it with
     * {@link ProcessingSpec.Builder#customTooltip}.
     */
    record Custom(@Nonnull ToDoubleFunction<ProcessingInputs> function, @Nonnull Set<ModifierKind> reads)
        implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return function.applyAsDouble(inputs);
        }
    }
}
