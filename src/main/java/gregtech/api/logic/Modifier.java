package gregtech.api.logic;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

import javax.annotation.Nonnull;

import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.GTStructureUtility;
import gregtech.common.misc.GTStructureChannels;

/**
 * A {@code Modifier} reads and writes the value of one machine for a {@link ModifierKind}. An {@link Of} constant is
 * also the structure element that writes the value: {@code .addElement('C', COIL)}.
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

    @Nonnull
    public static Modifier of(@Nonnull ModifierKind.LongKind kind, @Nonnull LongSupplier getter,
        @Nonnull LongConsumer setter) {
        return new Modifier(kind, getter, setter);
    }

    /** Before the first structure check, the value can be outside the spec's range. */
    public long get() {
        return getter.getAsLong();
    }

    /** Sets the value without a structure check. Machine code must not call this method. */
    public void set(long value) {
        setter.accept(value);
    }

    /** Accepts one coil type. */
    @Nonnull
    public static <T extends MTEMultiBlockBase> Of<T, HeatingCoilLevel> coil(
        @Nonnull Function<T, HeatingCoilLevel> getter, @Nonnull BiConsumer<T, HeatingCoilLevel> setter) {
        return new Of<>(machine -> Modifier.of(ModifierKind.COIL, () -> {
            HeatingCoilLevel coil = getter.apply(machine);
            return (coil == null ? HeatingCoilLevel.None : coil).getTier();
        }, tier -> setter.accept(machine, HeatingCoilLevel.getFromTier((byte) tier))),
            GTStructureChannels.HEATING_COIL
                .use(GTStructureUtility.activeCoils(GTStructureUtility.ofCoil(setter, getter))));
    }

    /** The getter returns -1 before the first structure check. */
    @Nonnull
    public static <T> Of<T, Integer> itemPipeCasing(@Nonnull Function<T, Integer> getter,
        @Nonnull BiConsumer<T, Integer> setter) {
        int absent = (int) ModifierKind.ITEM_PIPE_CASING.requireRange()
            .min() - 1;
        return new Of<>(machine -> Modifier.of(ModifierKind.ITEM_PIPE_CASING, () -> {
            Integer tier = getter.apply(machine);
            return tier == null ? absent : tier;
        }, tier -> setter.accept(machine, tier)), GTStructureUtility.chainItemPipeCasings(-1, setter, getter));
    }

    /** A structure element for one kind and machine class. {@link #of} returns the {@code Modifier} of one machine. */
    public static final class Of<T, V> extends GTStructureUtility.ProxyStructureElement<T, IStructureElement<T>> {

        private final Function<T, Modifier> modifierOf;

        private Of(Function<T, Modifier> modifierOf, IStructureElement<T> element) {
            super(element);
            this.modifierOf = modifierOf;
        }

        @Nonnull
        public Modifier of(@Nonnull T machine) {
            return modifierOf.apply(machine);
        }
    }
}
