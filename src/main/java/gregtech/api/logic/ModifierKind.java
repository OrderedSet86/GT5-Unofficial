package gregtech.api.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.util.StatCollector;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.util.tooltip.TooltipHelper;

/**
 * A kind of value a multiblock's recipe numbers read, such as its heating coil tier, an item it holds or its momentum.
 * A {@link ProcessingSpec} reads each kind's value from its {@link ProcessingSpec.Inputs}, and a machine declares where
 * its values come from as {@link Modifier}s.
 * <p>
 * The kinds many machines share are the constants here. A kind only one machine has is registered in that machine's
 * class, such as {@code ModifierKind.builder("kubatech:electrode")...register()}.
 */
public final class ModifierKind {

    /** Where a value comes from, which tells a planner how to ask for it. */
    public enum Source {
        /** The energy hatches. */
        ENERGY,
        /** A block the structure check finds. */
        STRUCTURE,
        /** An item the machine holds, such as an upgrade chip. */
        ITEM,
        /** State the machine builds up while it runs, such as momentum. */
        RUNTIME
    }

    private static final Map<String, ModifierKind> REGISTRY = new LinkedHashMap<>();

    /** The energy hatch tier, as {@link gregtech.api.util.GTUtility#getTier} numbers it. */
    public static final ModifierKind VOLTAGE = builder("gregtech:voltage").name("GT5U.MBTT.Tiers.Voltage")
        .source(Source.ENERGY)
        .ordered()
        .labels(tier -> GTValues.VN[tier])
        .register();
    /** {@link HeatingCoilLevel#getTier()}. */
    public static final ModifierKind COIL = builder("gregtech:coil").name("GT5U.MBTT.Tiers.Coil")
        .ordered()
        .labels(
            tier -> HeatingCoilLevel.getFromTier((byte) tier)
                .getName())
        .register();
    /** The voltage tier of the solenoids. */
    public static final ModifierKind SOLENOID = builder("gregtech:solenoid").name("GT5U.MBTT.Tiers.Solenoid")
        .ordered()
        .labels(tier -> GTValues.VN[tier])
        .register();
    /** 1 for the lowest item pipe casing. */
    public static final ModifierKind ITEM_PIPE_CASING = builder("gregtech:item_pipe_casing")
        .name("GT5U.MBTT.Tiers.ItemPipe")
        .ordered()
        .register();
    /** 1 for the lowest pipe casing. */
    public static final ModifierKind PIPE_CASING = builder("gregtech:pipe_casing").name("GT5U.MBTT.Tiers.FluidPipe")
        .ordered()
        .register();
    /** The count of repeated slices or layers. */
    public static final ModifierKind LENGTH = builder("gregtech:length").name("GT5U.MBTT.Tiers.Length")
        .ordered()
        .register();
    /** The voltage tier of the glass. */
    public static final ModifierKind GLASS = builder("gregtech:glass").name("GT5U.MBTT.Tiers.Glass")
        .ordered()
        .labels(tier -> GTValues.VN[tier])
        .register();

    @Nonnull
    public final String id;
    @Nonnull
    public final String nameKey;
    @Nonnull
    public final Source source;
    /** A higher value always makes the better machine, so a planner may start at the highest. */
    public final boolean ordered;
    @Nullable
    private final IntFunction<String> labels;

    private ModifierKind(Builder b) {
        this.id = b.id;
        this.nameKey = b.nameKey;
        this.source = b.source;
        this.ordered = b.ordered;
        this.labels = b.labels;
    }

    /** Such as "Heating Coil". */
    @Nonnull
    public String getName() {
        return StatCollector.translateToLocal(nameKey);
    }

    /** The name as tooltips show a tier, such as "Heating Coil" in its tier colour. */
    @Nonnull
    public String getTierText() {
        return TooltipHelper.tierText(getName());
    }

    /** A name for the value, such as "LuV" or "High Pressure"; the number itself when the kind has no names. */
    @Nonnull
    public String label(int value) {
        return labels == null ? String.valueOf(value) : StatCollector.translateToLocal(labels.apply(value));
    }

    @Override
    public String toString() {
        return id;
    }

    /**
     * Every registered kind, in the order they were registered. Complete only once the machines are registered, since
     * machine classes register their own kinds.
     */
    @Nonnull
    public static List<ModifierKind> all() {
        return Collections.unmodifiableList(new ArrayList<>(REGISTRY.values()));
    }

    @Nullable
    public static ModifierKind byId(@Nonnull String id) {
        return REGISTRY.get(id);
    }

    /** @param id Namespaced by mod, such as {@code "gregtech:coil"} */
    @Nonnull
    public static Builder builder(@Nonnull String id) {
        return new Builder(id);
    }

    /** One of {@code values}, numbered by ordinal and labelled by constant name. Not ordered. */
    @Nonnull
    public static <E extends Enum<E>> Builder ofEnum(@Nonnull String id, @Nonnull E[] values) {
        return builder(id).labels(index -> index >= 0 && index < values.length ? values[index].name() : "None");
    }

    public static final class Builder {

        private final String id;
        private String nameKey;
        private Source source = Source.STRUCTURE;
        private boolean ordered;
        private IntFunction<String> labels;

        private Builder(String id) {
            this.id = id;
            this.nameKey = id;
        }

        /** A translation key, or the name itself. */
        public Builder name(@Nonnull String nameKey) {
            this.nameKey = nameKey;
            return this;
        }

        /** {@link Source#STRUCTURE} unless set. */
        public Builder source(@Nonnull Source source) {
            this.source = source;
            return this;
        }

        /** See {@link ModifierKind#ordered}. */
        public Builder ordered() {
            this.ordered = true;
            return this;
        }

        public Builder labels(@Nonnull IntFunction<String> labels) {
            this.labels = labels;
            return this;
        }

        /** Names or translation keys for the values from {@code first} up, one each. */
        public Builder labels(int first, @Nonnull String... labels) {
            return labels(
                value -> value - first >= 0 && value - first < labels.length ? labels[value - first]
                    : String.valueOf(value));
        }

        /** @throws IllegalStateException if a kind with this id is registered already */
        @Nonnull
        public ModifierKind register() {
            ModifierKind kind = new ModifierKind(this);
            if (REGISTRY.putIfAbsent(id, kind) != null) {
                throw new IllegalStateException("modifier kind " + id + " is registered already");
            }
            return kind;
        }
    }
}
