package gregtech.api.logic;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
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
 * Where one machine gets the value of a {@link ModifierKind} its recipe numbers read, such as its coil tier from its
 * structure check or its momentum while it runs. For external tools such as factory planners, which inspect a machine
 * without a world; see {@link MTEMultiBlockBase#getModifiersForInspection()}.
 * <p>
 * A modifier the structure check sets through a structure element is declared once, as an {@link Of} constant that is
 * that structure element and that {@link MTEMultiBlockBase#getModifiersForInspection()} binds with {@link Of#of}:
 * {@code .addElement('C', COIL)}.
 */
public final class Modifier {

    @Nonnull
    public final ModifierKind kind;
    public final int min;
    public final int max;
    private final IntSupplier getter;
    private final IntConsumer setter;

    private Modifier(@Nonnull ModifierKind kind, int min, int max, @Nonnull IntSupplier getter,
        @Nonnull IntConsumer setter) {
        if (min > max) throw new IllegalArgumentException(kind + ": min " + min + " > max " + max);
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.getter = getter;
        this.setter = setter;
    }

    /** May be outside {@link #min} to {@link #max} while the structure is unchecked. */
    public int get() {
        return getter.getAsInt();
    }

    /**
     * Sets the value as if the machine had found it, including anything the machine derives from it.
     *
     * @throws IllegalArgumentException if the value is outside {@link #min} to {@link #max}
     */
    public void set(int value) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(kind + " " + value + " is outside " + min + " to " + max);
        }
        setter.accept(value);
    }

    /** For a value the machine sets outside a structure element, such as after its structure check. */
    @Nonnull
    public static Builder builder(@Nonnull ModifierKind kind) {
        return new Builder(kind);
    }

    /**
     * Heating coils, for a machine that stores the {@link HeatingCoilLevel} its check found. The element accepts one
     * coil type, is set from the heating coil channel and records the coils it finds as the machine's active coils.
     */
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

    /**
     * Solenoids, for a machine that stores the voltage tier its check found, as solenoid blocks report it. The element
     * accepts one solenoid tier and is set from the solenoid channel.
     */
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

    /**
     * Item pipe casings, from 1 for the lowest, as {@link GTStructureUtility#chainItemPipeCasings} reports them; -1
     * before the check finds one.
     */
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

    /**
     * One of {@code values}, such as an electrode, numbered by ordinal as {@link ModifierKind#ofEnum} labels it; -1
     * while there is none.
     */
    @Nonnull
    public static <E extends Enum<E>> Modifier ofEnum(@Nonnull ModifierKind kind, @Nonnull E[] values,
        @Nonnull Supplier<E> getter, @Nonnull Consumer<E> setter) {
        return new Modifier(kind, 0, values.length - 1, () -> {
            E value = getter.get();
            return value == null ? -1 : value.ordinal();
        }, index -> setter.accept(values[index]));
    }

    /**
     * Any other value a structure element reports as a number, such as a casing tier from {@code ofBlocksTiered}.
     *
     * @param element Builds the structure element from the setter and getter; wrap it in {@code lazy} if it names
     *                blocks, which may not exist yet when the machine class loads
     */
    @Nonnull
    public static <T> Of<T, Integer> tiered(@Nonnull ModifierKind kind, int min, int max,
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

    /**
     * A modifier declared once for a machine class. It is the structure element that finds the value, and {@link #of}
     * binds it to one machine for inspection.
     *
     * @param <T> The machine
     * @param <V> The value the machine stores, such as a {@link HeatingCoilLevel}
     */
    public static final class Of<T, V> extends GTStructureUtility.ProxyStructureElement<T, IStructureElement<T>> {

        private final ModifierKind kind;
        private final int min;
        private final int max;
        private final Function<T, V> getter;
        private final BiConsumer<T, V> setter;
        private final ToIntFunction<V> toTier;
        private final IntFunction<V> fromTier;

        private Of(ModifierKind kind, int min, int max, Function<T, V> getter, BiConsumer<T, V> setter,
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
         * Runs {@code derive} after each {@link Modifier#set}, for a machine that works out other values from this one
         * once its structure check is done. The structure element is unchanged: the machine derives them itself after
         * the check.
         */
        @Nonnull
        public Of<T, V> derivingAfterSet(@Nonnull Consumer<T> derive) {
            return new Of<>(kind, min, max, getter, (machine, value) -> {
                setter.accept(machine, value);
                derive.accept(machine);
            }, toTier, fromTier, proxiedElement);
        }

        @Nonnull
        public Modifier of(@Nonnull T machine) {
            return new Modifier(
                kind,
                min,
                max,
                () -> toTier.applyAsInt(getter.apply(machine)),
                tier -> setter.accept(machine, fromTier.apply(tier)));
        }
    }

    public static final class Builder {

        private final ModifierKind kind;
        private int min;
        private int max;
        private IntSupplier getter;
        private IntConsumer setter;

        private Builder(ModifierKind kind) {
            this.kind = kind;
        }

        public Builder between(int min, int max) {
            this.min = min;
            this.max = max;
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
        public Modifier build() {
            if (getter == null || setter == null)
                throw new IllegalStateException(kind + " needs a getter and a setter");
            return new Modifier(kind, min, max, getter, setter);
        }
    }
}
