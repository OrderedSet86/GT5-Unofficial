package gregtech.api.logic;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;

import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.enums.VoltageIndex;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.GTStructureUtility;
import gregtech.common.misc.GTStructureChannels;

/**
 * Where one machine gets a {@link ModifierKind}'s value. An {@link Of} constant is also the structure element that
 * finds it: {@code .addElement('C', COIL)}.
 */
public final class Modifier<T extends Number & Comparable<T>> {

    @Nonnull
    public final ModifierKind<T> kind;
    @Nonnull
    public final T min;
    @Nonnull
    public final T max;
    private final Supplier<T> getter;
    private final Consumer<T> setter;

    private Modifier(@Nonnull ModifierKind<T> kind, @Nonnull T min, @Nonnull T max, @Nonnull Supplier<T> getter,
        @Nonnull Consumer<T> setter) {
        if (min.compareTo(max) > 0) throw new IllegalArgumentException(kind + ": min " + min + " > max " + max);
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.getter = getter;
        this.setter = setter;
    }

    /** Outside the range while the structure is unchecked. */
    @Nonnull
    public T get() {
        return getter.get();
    }

    /**
     * As if the machine had found it, including values it derives.
     *
     * @throws IllegalArgumentException if the value is out of range
     */
    public void set(@Nonnull T value) {
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw new IllegalArgumentException(kind + " " + value + " is outside " + min + " to " + max);
        }
        setter.accept(value);
    }

    /** As {@link #set}, at the best value of an ordered kind. */
    public void setToMax() {
        set(max);
    }

    /** For a value set outside a structure element. */
    @Nonnull
    public static <T extends Number & Comparable<T>> Builder<T> builder(@Nonnull ModifierKind<T> kind) {
        return new Builder<>(kind);
    }

    /** Accepts one coil type, uses the heating coil channel and records the active coils. */
    @Nonnull
    public static <T extends MTEMultiBlockBase> Of<T, HeatingCoilLevel> coil(
        @Nonnull Function<T, HeatingCoilLevel> getter, @Nonnull BiConsumer<T, HeatingCoilLevel> setter) {
        return new Of<>(
            ModifierKind.COIL,
            0,
            HeatingCoilLevel.getMaxTier(),
            getter,
            setter,
            coil -> (coil == null ? HeatingCoilLevel.None : coil).getTier(),
            tier -> HeatingCoilLevel.getFromTier((byte) tier),
            GTStructureChannels.HEATING_COIL
                .use(GTStructureUtility.activeCoils(GTStructureUtility.ofCoil(setter, getter))));
    }

    /** Accepts one solenoid tier and uses the solenoid channel. */
    @Nonnull
    public static <T> Of<T, Byte> solenoid(@Nonnull Function<T, Byte> getter, @Nonnull BiConsumer<T, Byte> setter) {
        return new Of<>(
            ModifierKind.SOLENOID,
            VoltageIndex.MV,
            VoltageIndex.UMV,
            getter,
            setter,
            tier -> tier == null ? 0 : tier,
            tier -> (byte) tier,
            GTStructureChannels.SOLENOID.use(GTStructureUtility.ofSolenoidCoil(setter, getter)));
    }

    /** -1 before the check finds one. */
    @Nonnull
    public static <T> Of<T, Integer> itemPipeCasing(@Nonnull Function<T, Integer> getter,
        @Nonnull BiConsumer<T, Integer> setter) {
        return tiered(
            ModifierKind.ITEM_PIPE_CASING,
            1,
            GTStructureUtility.ITEM_PIPE_CASING_TIERS,
            getter,
            setter,
            (set, get) -> GTStructureUtility.chainItemPipeCasings(-1, set, get));
    }

    /** -1 while there is none. */
    @Nonnull
    public static <E extends Enum<E>> Modifier<Integer> ofEnum(@Nonnull ModifierKind<Integer> kind, @Nonnull E[] values,
        @Nonnull Supplier<E> getter, @Nonnull Consumer<E> setter) {
        return new Modifier<>(kind, 0, values.length - 1, () -> {
            E value = getter.get();
            return value == null ? -1 : value.ordinal();
        }, index -> setter.accept(values[index]));
    }

    /**
     * @param element Wrap it in {@code lazy} if it names blocks, which may not exist yet when the machine class loads
     */
    @Nonnull
    public static <T> Of<T, Integer> tiered(@Nonnull ModifierKind<Integer> kind, int min, int max,
        @Nonnull Function<T, Integer> getter, @Nonnull BiConsumer<T, Integer> setter,
        @Nonnull BiFunction<BiConsumer<T, Integer>, Function<T, Integer>, IStructureElement<T>> element) {
        return new Of<>(
            kind,
            min,
            max,
            getter,
            setter,
            tier -> tier == null ? min - 1 : tier,
            tier -> tier,
            element.apply(setter, getter));
    }

    /** Bound to one machine with {@link #of}. */
    public static final class Of<T, V> extends GTStructureUtility.ProxyStructureElement<T, IStructureElement<T>> {

        private final ModifierKind<Integer> kind;
        private final int min;
        private final int max;
        private final Function<T, V> getter;
        private final BiConsumer<T, V> setter;
        private final ToIntFunction<V> toTier;
        private final IntFunction<V> fromTier;

        private Of(ModifierKind<Integer> kind, int min, int max, Function<T, V> getter, BiConsumer<T, V> setter,
            ToIntFunction<V> toTier, IntFunction<V> fromTier, IStructureElement<T> element) {
            super(element);
            this.kind = kind;
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
            this.toTier = toTier;
            this.fromTier = fromTier;
        }

        /**
         * For values the machine derives after its structure check. The structure element does not run {@code derive}.
         */
        @Nonnull
        public Of<T, V> derivingAfterSet(@Nonnull Consumer<T> derive) {
            return new Of<>(kind, min, max, getter, (machine, value) -> {
                setter.accept(machine, value);
                derive.accept(machine);
            }, toTier, fromTier, proxiedElement);
        }

        @Nonnull
        public Modifier<Integer> of(@Nonnull T machine) {
            return new Modifier<>(
                kind,
                min,
                max,
                () -> toTier.applyAsInt(getter.apply(machine)),
                tier -> setter.accept(machine, fromTier.apply(tier)));
        }
    }

    public static final class Builder<T extends Number & Comparable<T>> {

        private final ModifierKind<T> kind;
        private T min;
        private T max;
        private Supplier<T> getter;
        private Consumer<T> setter;

        private Builder(ModifierKind<T> kind) {
            this.kind = kind;
        }

        public Builder<T> between(@Nonnull T min, @Nonnull T max) {
            this.min = min;
            this.max = max;
            return this;
        }

        public Builder<T> getter(@Nonnull Supplier<T> getter) {
            this.getter = getter;
            return this;
        }

        public Builder<T> setter(@Nonnull Consumer<T> setter) {
            this.setter = setter;
            return this;
        }

        @Nonnull
        public Modifier<T> build() {
            if (min == null || getter == null || setter == null) {
                throw new IllegalStateException(kind + " needs a range, a getter and a setter");
            }
            return new Modifier<>(kind, min, max, getter, setter);
        }
    }
}
