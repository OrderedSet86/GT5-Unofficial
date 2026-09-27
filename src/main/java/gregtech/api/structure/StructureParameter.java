package gregtech.api.structure;

import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.enums.VoltageIndex;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.tooltip.TooltipTier;

/**
 * One value a multiblock's structure check finds that its recipe numbers read, such as its coil or solenoid tier. For
 * external tools such as factory planners, which inspect a machine without a world; see
 * {@link MTEMultiBlockBase#getStructureParametersForInspection()}.
 * <p>
 * Values use each kind's usual tier number, whatever the machine stores internally:
 * <ul>
 * <li>{@link TooltipTier#COIL}: {@link gregtech.api.enums.HeatingCoilLevel#getTier()}</li>
 * <li>{@link TooltipTier#SOLENOID} and {@link TooltipTier#GLASS}: the voltage tier</li>
 * <li>{@link TooltipTier#ITEM_PIPE_CASING} and {@link TooltipTier#PIPE_CASING}: 1 for the lowest casing</li>
 * <li>{@link TooltipTier#LENGTH}: the count of repeated slices or layers</li>
 * <li>anything else: the machine's own numbering, between {@link #min} and {@link #max}</li>
 * </ul>
 */
public final class StructureParameter {

    @Nonnull
    public final TooltipTier kind;
    public final int min;
    public final int max;
    private final IntSupplier getter;
    private final IntConsumer setter;

    public StructureParameter(@Nonnull TooltipTier kind, int min, int max, @Nonnull IntSupplier getter,
        @Nonnull IntConsumer setter) {
        if (min > max) throw new IllegalArgumentException(kind + ": min " + min + " > max " + max);
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.getter = getter;
        this.setter = setter;
    }

    /** A heating coil, for a machine that stores the {@link HeatingCoilLevel} its check found. */
    @Nonnull
    public static StructureParameter coil(@Nonnull Supplier<HeatingCoilLevel> getter,
        @Nonnull Consumer<HeatingCoilLevel> setter) {
        return new StructureParameter(TooltipTier.COIL, 0, HeatingCoilLevel.getMaxTier(), () -> {
            HeatingCoilLevel coil = getter.get();
            return (coil == null ? HeatingCoilLevel.None : coil).getTier();
        }, tier -> setter.accept(HeatingCoilLevel.getFromTier((byte) tier)));
    }

    /** A solenoid, for a machine that stores the voltage tier its check found, as solenoid blocks report it. */
    @Nonnull
    public static StructureParameter solenoid(@Nonnull Supplier<Byte> getter, @Nonnull Consumer<Byte> setter) {
        return new StructureParameter(TooltipTier.SOLENOID, VoltageIndex.MV, VoltageIndex.UMV, () -> {
            Byte tier = getter.get();
            return tier == null ? 0 : tier;
        }, tier -> setter.accept((byte) tier));
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
}
