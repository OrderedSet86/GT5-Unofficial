package gregtech.api.logic;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;

import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.GTStructureUtility;
import gregtech.common.misc.GTStructureChannels;

/**
 * The getter and setter of one machine's value for a {@link ModifierKind}. The machine's spec inputs read it, and the
 * conformance check sets it on a copy of the machine. Planners read the kinds and ranges from
 * {@link ProcessingSpec#getModifiers()}. An {@link Of} constant is also the structure element that sets the value:
 * {@code .addElement('C', COIL)}.
 */
public final class Modifier {

    @Nonnull
    public final ModifierKind kind;
    private final LongSupplier getter;
    private final LongConsumer setter;

    private Modifier(@Nonnull ModifierKind kind, @Nonnull LongSupplier getter, @Nonnull LongConsumer setter) {
        this.kind = kind;
        this.getter = getter;
        this.setter = setter;
    }

    @Nonnull
    public static Modifier of(@Nonnull ModifierKind.IntKind kind, @Nonnull IntSupplier getter,
        @Nonnull IntConsumer setter) {
        return new Modifier(kind, getter::getAsInt, value -> setter.accept(Math.toIntExact(value)));
    }

    /** Outside the spec's range while the structure is unchecked. */
    public long get() {
        return getter.getAsLong();
    }

    /** As the structure check would set it. Machine code must not call this. */
    public void set(long value) {
        setter.accept(value);
    }

    /** Accepts one coil type. */
    @Nonnull
    public static <T extends MTEMultiBlockBase> Of<T, HeatingCoilLevel> coil(
        @Nonnull Function<T, HeatingCoilLevel> getter, @Nonnull BiConsumer<T, HeatingCoilLevel> setter) {
        return new Of<>(
            ModifierKind.COIL,
            getter,
            setter,
            coil -> (coil == null ? HeatingCoilLevel.None : coil).getTier(),
            tier -> HeatingCoilLevel.getFromTier((byte) tier),
            GTStructureChannels.HEATING_COIL
                .use(GTStructureUtility.activeCoils(GTStructureUtility.ofCoil(setter, getter))));
    }

    /** -1 until the structure check sets it. */
    @Nonnull
    public static <T> Of<T, Integer> itemPipeCasing(@Nonnull Function<T, Integer> getter,
        @Nonnull BiConsumer<T, Integer> setter) {
        return tiered(
            ModifierKind.ITEM_PIPE_CASING,
            getter,
            setter,
            (set, get) -> GTStructureUtility.chainItemPipeCasings(-1, set, get));
    }

    /**
     * @param element Wrap it in {@code lazy} if it refers to blocks, which may not exist yet when the machine class
     *                loads
     */
    @Nonnull
    public static <T> Of<T, Integer> tiered(@Nonnull ModifierKind.IntKind kind, @Nonnull Function<T, Integer> getter,
        @Nonnull BiConsumer<T, Integer> setter,
        @Nonnull BiFunction<BiConsumer<T, Integer>, Function<T, Integer>, IStructureElement<T>> element) {
        ModifierRange range = kind.getRange();
        int absent = range == null ? -1 : (int) range.min() - 1;
        return new Of<>(
            kind,
            getter,
            setter,
            tier -> tier == null ? absent : tier,
            tier -> tier,
            element.apply(setter, getter));
    }

    /** Bound to one machine with {@link #of}. */
    public static final class Of<T, V> extends GTStructureUtility.ProxyStructureElement<T, IStructureElement<T>> {

        private final ModifierKind.IntKind kind;
        private final Function<T, V> getter;
        private final BiConsumer<T, V> setter;
        private final ToIntFunction<V> toTier;
        private final IntFunction<V> fromTier;

        private Of(ModifierKind.IntKind kind, Function<T, V> getter, BiConsumer<T, V> setter, ToIntFunction<V> toTier,
            IntFunction<V> fromTier, IStructureElement<T> element) {
            super(element);
            this.kind = kind;
            this.getter = getter;
            this.setter = setter;
            this.toTier = toTier;
            this.fromTier = fromTier;
        }

        @Nonnull
        public Modifier of(@Nonnull T machine) {
            return Modifier.of(
                kind,
                () -> toTier.applyAsInt(getter.apply(machine)),
                tier -> setter.accept(machine, fromTier.apply(tier)));
        }
    }
}
