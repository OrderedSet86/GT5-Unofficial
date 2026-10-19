package gregtech.api.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import gregtech.api.enums.GTValues;
import gregtech.api.util.GTUtility;

/**
 * What a {@link ProcessingSpec} reads: the energy hatches, the machine mode and a value per {@link ModifierKind}.
 *
 * @param energyHatches What the machine draws from, including hatches it cannot reach
 *                      ({@link EnergyHatch#disconnected})
 */
public record ProcessingInputs(@Nonnull List<EnergyHatch> energyHatches, int mode,
    @Nonnull Map<ModifierKind, Long> values) {

    public ProcessingInputs {
        energyHatches = List.copyOf(energyHatches);
        values = Map.copyOf(values);
    }

    public record EnergyHatch(long voltage, long amperage, boolean exotic) {

        /** An invalid hatch, which the average voltage includes and the amperage and EU sums leave out. */
        @Nonnull
        public static EnergyHatch disconnected(boolean exotic) {
            return new EnergyHatch(0, 0, exotic);
        }

        /** Supplies 2 A. */
        @Nonnull
        public static EnergyHatch regular(int tier) {
            return new EnergyHatch(GTValues.V[tier], 2, false);
        }

        /** A multi-amp or laser hatch, whose amps are all used. */
        @Nonnull
        public static EnergyHatch exotic(int tier, long amperage) {
            return new EnergyHatch(GTValues.V[tier], amperage, true);
        }
    }

    /**
     * For {@link ModifierKind#VOLTAGE}, the value is {@link #voltageTier}.
     *
     * @throws IllegalArgumentException if no value was given for the kind
     */
    public int value(@Nonnull ModifierKind.IntKind kind) {
        if (kind == ModifierKind.VOLTAGE) return voltageTier();
        return (int) rawValue(kind);
    }

    public long value(@Nonnull ModifierKind kind) {
        return switch (kind) {
            case ModifierKind.IntKind tier -> value(tier);
        };
    }

    public boolean has(@Nonnull ModifierKind kind) {
        return kind == ModifierKind.VOLTAGE || values.containsKey(kind);
    }

    private long rawValue(ModifierKind kind) {
        Long value = values.get(kind);
        if (value == null) throw new IllegalArgumentException("no " + kind + " value given");
        return value;
    }

    /** The tier of {@link #maxInputVoltage()}. */
    public int voltageTier() {
        return GTUtility.getTier(maxInputVoltage());
    }

    /** 0 without hatches. */
    public long averageVoltage() {
        return energyHatches.isEmpty() ? 0 : maxInputVoltage() / energyHatches.size();
    }

    /** The sum of the hatch voltages. */
    public long maxInputVoltage() {
        long voltage = 0;
        for (EnergyHatch hatch : energyHatches) voltage += hatch.voltage;
        return voltage;
    }

    public long amperage() {
        long amperage = 0;
        for (EnergyHatch hatch : energyHatches) amperage += hatch.amperage;
        return amperage;
    }

    /** Saturates at {@link Long#MAX_VALUE}. */
    public long totalEu() {
        long eu = 0;
        for (EnergyHatch hatch : energyHatches)
            eu = GTUtility.addSafe(eu, GTUtility.mulSafe(hatch.voltage, hatch.amperage));
        return eu;
    }

    public boolean isSingleRegularHatch() {
        return energyHatches.size() == 1 && !energyHatches.get(0).exotic;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    @Nonnull
    public Builder toBuilder() {
        Builder builder = new Builder().energyHatches(energyHatches)
            .mode(mode);
        builder.values.putAll(values);
        return builder;
    }

    public static final class Builder {

        private final List<EnergyHatch> energyHatches = new ArrayList<>();
        private int mode;
        private final Map<ModifierKind, Long> values = new HashMap<>();

        private Builder() {}

        public Builder energyHatch(@Nonnull EnergyHatch hatch) {
            this.energyHatches.add(hatch);
            return this;
        }

        /** {@code count} regular hatches. */
        public Builder energyHatches(int tier, int count) {
            for (int i = 0; i < count; i++) energyHatch(EnergyHatch.regular(tier));
            return this;
        }

        public Builder energyHatches(@Nonnull List<EnergyHatch> hatches) {
            this.energyHatches.addAll(hatches);
            return this;
        }

        public Builder mode(int mode) {
            this.mode = mode;
            return this;
        }

        /**
         * @throws IllegalArgumentException for {@link ModifierKind#VOLTAGE}. The voltage tier is read from the energy
         *                                  hatches
         */
        public Builder value(@Nonnull ModifierKind.IntKind kind, int value) {
            if (kind == ModifierKind.VOLTAGE) {
                throw new IllegalArgumentException(
                    "the voltage tier is read from the energy hatches. Add them with energyHatch()");
            }
            this.values.put(kind, (long) value);
            return this;
        }

        /** For a value taken from a {@link ModifierKind.Range}, whose bounds are longs. */
        public Builder value(@Nonnull ModifierKind kind, long value) {
            return switch (kind) {
                case ModifierKind.IntKind tier -> value(tier, Math.toIntExact(value));
            };
        }

        public Builder modifiers(@Nonnull List<Modifier> modifiers) {
            for (Modifier modifier : modifiers) this.values.put(modifier.kind, modifier.get());
            return this;
        }

        @Nonnull
        public ProcessingInputs build() {
            return new ProcessingInputs(energyHatches, mode, values);
        }
    }
}
