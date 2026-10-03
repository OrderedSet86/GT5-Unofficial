package gregtech.api.logic;

import java.util.List;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nonnull;

import gregtech.api.util.GTUtility;

/**
 * One number of a {@link ProcessingSpec} as data, so a planner can show how it is made and which kinds it reads, and
 * the tooltip can be written from it. Speeds are kept as speeds: 2.5 is 250%.
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

    /** {@code base + perTier * (tier - firstTier)}, where the tier is {@link ModifierKind.IntKind#countedTier}. */
    record PerTier(double base, double perTier, @Nonnull ModifierKind.IntKind kind, int firstTier) implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return base + perTier * (kind.countedTier(inputs.value(kind)) - firstTier);
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

    /** {@code factor} times the product of the kinds' tiers. */
    record TierProduct(int factor, @Nonnull List<ModifierKind.IntKind> kinds) implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            int product = factor;
            for (ModifierKind.IntKind kind : kinds) product *= kind.countedTier(inputs.value(kind));
            return product;
        }

        @Nonnull
        @Override
        public Set<ModifierKind> reads() {
            return Set.copyOf(kinds);
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
     * From {@code min} to {@code max} per voltage tier as {@code kind} rises from 0 to {@code kindMax}. In float, so
     * truncation to whole parallels matches the machines' code.
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

    /** The formula, but never below {@code floor}. */
    record AtLeast(@Nonnull Formula formula, double floor) implements Formula {

        @Override
        public double apply(@Nonnull ProcessingInputs inputs) {
            return Math.max(formula.apply(inputs), floor);
        }

        @Nonnull
        @Override
        public Set<ModifierKind> reads() {
            return formula.reads();
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
