package gregtech.api.structure;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.enums.VoltageIndex;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.GTStructureUtility;
import gregtech.api.util.tooltip.TooltipTier;

/**
 * One value a multiblock's structure check finds that its recipe numbers read, such as its coil or solenoid tier. For
 * external tools such as factory planners, which inspect a machine without a world; see
 * {@link MTEMultiBlockBase#getStructureParametersForInspection()}.
 * <p>
 * Values use each kind's usual tier number, whatever the machine stores internally:
 * <ul>
 * <li>{@link TooltipTier#COIL}: {@link HeatingCoilLevel#getTier()}</li>
 * <li>{@link TooltipTier#SOLENOID} and {@link TooltipTier#GLASS}: the voltage tier</li>
 * <li>{@link TooltipTier#ITEM_PIPE_CASING} and {@link TooltipTier#PIPE_CASING}: 1 for the lowest casing</li>
 * <li>{@link TooltipTier#LENGTH}: the count of repeated slices or layers</li>
 * <li>anything else: the machine's own numbering, between {@link #min} and {@link #max}</li>
 * </ul>
 * A parameter the structure check sets through a structure element is declared once, as an {@link Of} constant that
 * both the element and {@link MTEMultiBlockBase#getStructureParametersForInspection()} use.
 */
public final class StructureParameter {

    @Nonnull
    public final TooltipTier kind;
    public final int min;
    public final int max;
    private final IntSupplier getter;
    private final IntConsumer setter;
    @Nullable
    private final IntFunction<String> labels;

    private StructureParameter(@Nonnull TooltipTier kind, int min, int max, @Nonnull IntSupplier getter,
        @Nonnull IntConsumer setter, @Nullable IntFunction<String> labels) {
        if (min > max) throw new IllegalArgumentException(kind + ": min " + min + " > max " + max);
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.getter = getter;
        this.setter = setter;
        this.labels = labels;
    }

    /** May be outside {@link #min} to {@link #max} while the structure is unchecked. */
    public int get() {
        return getter.getAsInt();
    }

    /**
     * Sets the value as if the structure check had found it, including anything the machine derives from it.
     *
     * @throws IllegalArgumentException if the value is outside {@link #min} to {@link #max}
     */
    public void set(int value) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(kind + " " + value + " is outside " + min + " to " + max);
        }
        setter.accept(value);
    }

    /** A name for the value, such as "LuV" or "High Pressure"; the number itself when the kind has no names. */
    @Nonnull
    public String label(int value) {
        return labels == null ? String.valueOf(value) : labels.apply(value);
    }

    /** For a parameter the machine sets outside a structure element, such as after its structure check. */
    @Nonnull
    public static Builder builder(@Nonnull TooltipTier kind) {
        return new Builder(kind);
    }

    /** A heating coil, for a machine that stores the {@link HeatingCoilLevel} its check found. */
    @Nonnull
    public static <T> Of<T, HeatingCoilLevel> coil(@Nonnull Function<T, HeatingCoilLevel> getter,
        @Nonnull BiConsumer<T, HeatingCoilLevel> setter) {
        return new Of<>(
            TooltipTier.COIL,
            0,
            HeatingCoilLevel.getMaxTier(),
            getter,
            setter,
            coil -> (coil == null ? HeatingCoilLevel.None : coil).getTier(),
            tier -> HeatingCoilLevel.getFromTier((byte) tier),
            tier -> HeatingCoilLevel.getFromTier((byte) tier)
                .getName());
    }

    /** A solenoid, for a machine that stores the voltage tier its check found, as solenoid blocks report it. */
    @Nonnull
    public static <T> Of<T, Byte> solenoid(@Nonnull Function<T, Byte> getter, @Nonnull BiConsumer<T, Byte> setter) {
        return new Of<>(
            TooltipTier.SOLENOID,
            VoltageIndex.MV,
            VoltageIndex.UMV,
            getter,
            setter,
            tier -> tier == null ? 0 : tier,
            tier -> (byte) tier,
            tier -> GTValues.VN[tier]);
    }

    /** An item pipe casing, from 1 for the lowest, as {@link GTStructureUtility#chainItemPipeCasings} reports it. */
    @Nonnull
    public static <T> Of<T, Integer> itemPipeCasing(@Nonnull Function<T, Integer> getter,
        @Nonnull BiConsumer<T, Integer> setter) {
        return tiered(TooltipTier.ITEM_PIPE_CASING, 1, GTStructureUtility.ITEM_PIPE_CASING_TIERS, getter, setter);
    }

    /**
     * One of a list of parts, such as a sawblade, numbered by its position in {@code values}; -1 while there is none.
     */
    @Nonnull
    public static <E extends Enum<E>> StructureParameter ofEnum(@Nonnull TooltipTier kind, @Nonnull E[] values,
        @Nonnull Supplier<E> getter, @Nonnull Consumer<E> setter) {
        return new StructureParameter(kind, 0, values.length - 1, () -> {
            E value = getter.get();
            return value == null ? -1 : value.ordinal();
        }, index -> setter.accept(values[index]), index -> values[index].name());
    }

    /** Any other value a structure element reports as a number, such as a casing tier from {@code ofBlocksTiered}. */
    @Nonnull
    public static <T> Of<T, Integer> tiered(@Nonnull TooltipTier kind, int min, int max,
        @Nonnull Function<T, Integer> getter, @Nonnull BiConsumer<T, Integer> setter) {
        return new Of<>(kind, min, max, getter, setter, tier -> tier == null ? min - 1 : tier, tier -> tier, null);
    }

    /**
     * A parameter declared once for a machine class, which its structure element takes {@link #getter()} and
     * {@link #setter()} from, and which {@link #of} binds to one machine for inspection.
     *
     * @param <T> The machine
     * @param <V> The value the machine stores, such as a {@link HeatingCoilLevel}
     */
    public static final class Of<T, V> {

        private final TooltipTier kind;
        private final int min;
        private final int max;
        private final Function<T, V> getter;
        private final BiConsumer<T, V> setter;
        private final ToIntFunction<V> toTier;
        private final IntFunction<V> fromTier;
        @Nullable
        private final IntFunction<String> labels;

        private Of(TooltipTier kind, int min, int max, Function<T, V> getter, BiConsumer<T, V> setter,
            ToIntFunction<V> toTier, IntFunction<V> fromTier, @Nullable IntFunction<String> labels) {
            this.kind = kind;
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
            this.toTier = toTier;
            this.fromTier = fromTier;
            this.labels = labels;
        }

        /** For the structure element. */
        @Nonnull
        public Function<T, V> getter() {
            return getter;
        }

        /** For the structure element. */
        @Nonnull
        public BiConsumer<T, V> setter() {
            return setter;
        }

        /**
         * Runs {@code derive} after each {@link StructureParameter#set}, for a machine that works out other values from
         * this one once its structure check is done.
         */
        @Nonnull
        public Of<T, V> derivingAfterSet(@Nonnull Consumer<T> derive) {
            return new Of<>(kind, min, max, getter, (machine, value) -> {
                setter.accept(machine, value);
                derive.accept(machine);
            }, toTier, fromTier, labels);
        }

        @Nonnull
        public StructureParameter of(@Nonnull T machine) {
            return new StructureParameter(
                kind,
                min,
                max,
                () -> toTier.applyAsInt(getter.apply(machine)),
                tier -> setter.accept(machine, fromTier.apply(tier)),
                labels);
        }
    }

    public static final class Builder {

        private final TooltipTier kind;
        private int min;
        private int max;
        private IntSupplier getter;
        private IntConsumer setter;
        private IntFunction<String> labels;
        private String[] labelList;

        private Builder(TooltipTier kind) {
            this.kind = kind;
        }

        public Builder between(int min, int max) {
            this.min = min;
            this.max = max;
            return this;
        }

        /** Names for the values from {@link #between min} up, one each. */
        public Builder labels(@Nonnull String... labels) {
            this.labelList = labels;
            this.labels = null;
            return this;
        }

        public Builder labels(@Nonnull IntFunction<String> labels) {
            this.labels = labels;
            this.labelList = null;
            return this;
        }

        public Builder getter(@Nonnull IntSupplier getter) {
            this.getter = getter;
            return this;
        }

        public Builder setter(@Nonnull IntConsumer setter) {
            this.setter = setter;
            return this;
        }

        @Nonnull
        public StructureParameter build() {
            if (getter == null || setter == null)
                throw new IllegalStateException(kind + " needs a getter and a setter");
            IntFunction<String> names = labels;
            if (labelList != null) {
                String[] list = labelList;
                int first = min;
                names = value -> value - first >= 0 && value - first < list.length ? list[value - first]
                    : String.valueOf(value);
            }
            return new StructureParameter(kind, min, max, getter, setter, names);
        }
    }
}
