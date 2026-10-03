package gregtech.api.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.util.StatCollector;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.enums.VoltageIndex;
import gregtech.api.util.GTStructureUtility;
import gregtech.api.util.tooltip.TooltipHelper;

/**
 * A value a {@link ProcessingSpec} reads, such as a coil tier, an item count or momentum. Shared kinds are the
 * constants here. A kind only one machine has is registered in its class.
 * <p>
 * Sealed rather than generic over the value type, so a consumer that switches over the kinds without a default arm
 * stops compiling when a new value type is added, instead of failing at run time.
 */
public abstract sealed class ModifierKind permits ModifierKind.IntKind,ModifierKind.LongKind {

    /** Where a planner gets the value from. */
    public enum Source {
        ENERGY,
        STRUCTURE,
        /** Such as an upgrade chip. */
        ITEM,
        /** Built up while running, such as momentum. */
        RUNTIME
    }

    /** Tiers and counts. */
    public static final class IntKind extends ModifierKind {

        private IntKind(Builder<IntKind> builder) {
            super(builder);
        }

        /** The tier as per-tier terms and their tooltips count it. */
        public int countedTier(int value) {
            return value + tierOffset;
        }
    }

    /** Amounts that outgrow an int, such as stored fluid. */
    public static final class LongKind extends ModifierKind {

        private LongKind(Builder<LongKind> builder) {
            super(builder);
        }
    }

    private static final Map<String, ModifierKind> REGISTRY = new LinkedHashMap<>();

    /** Read from the energy hatches, so never given as a value. */
    public static final IntKind VOLTAGE = ofInt("gregtech:voltage").name("GT5U.MBTT.Tiers.Voltage")
        .source(Source.ENERGY)
        .ordered()
        .labels(tier -> GTValues.VN[(int) tier])
        .register();
    /** {@link HeatingCoilLevel#getTier()}. */
    public static final IntKind COIL = ofInt("gregtech:coil").name("GT5U.MBTT.Tiers.Coil")
        .ordered()
        .range(0, HeatingCoilLevel.getMaxTier())
        .tierOffset(1)
        .labels(
            tier -> HeatingCoilLevel.getFromTier((byte) tier)
                .getName())
        .register();
    /** The voltage tier of the solenoids. */
    public static final IntKind SOLENOID = ofInt("gregtech:solenoid").name("GT5U.MBTT.Tiers.Solenoid")
        .ordered()
        .range(VoltageIndex.MV, VoltageIndex.UMV)
        .labels(tier -> GTValues.VN[(int) tier])
        .register();
    /** 1 for the lowest item pipe casing. */
    public static final IntKind ITEM_PIPE_CASING = ofInt("gregtech:item_pipe_casing").name("GT5U.MBTT.Tiers.ItemPipe")
        .ordered()
        .range(1, GTStructureUtility.ITEM_PIPE_CASING_TIERS)
        .register();
    /** 1 for the lowest pipe casing. */
    public static final IntKind PIPE_CASING = ofInt("gregtech:pipe_casing").name("GT5U.MBTT.Tiers.FluidPipe")
        .ordered()
        .register();
    /** The count of repeated slices or layers. Each machine gives its own range. */
    public static final IntKind LENGTH = ofInt("gregtech:length").name("GT5U.MBTT.Tiers.Length")
        .ordered()
        .register();
    /** The voltage tier of the glass. */
    public static final IntKind GLASS = ofInt("gregtech:glass").name("GT5U.MBTT.Tiers.Glass")
        .ordered()
        .labels(tier -> GTValues.VN[(int) tier])
        .register();

    @Nonnull
    public final String id;
    @Nonnull
    public final String nameKey;
    @Nonnull
    public final Source source;
    /** A higher value makes the better machine, so a planner may start at the highest. */
    public final boolean ordered;
    @Nullable
    private final ModifierRange range;
    @Nullable
    private final LongFunction<String> labels;
    final int tierOffset;

    private ModifierKind(Builder<?> builder) {
        this.id = builder.id;
        this.nameKey = builder.nameKey;
        this.source = builder.source;
        this.ordered = builder.ordered;
        this.range = builder.hasRange ? new ModifierRange(this, builder.min, builder.max) : null;
        this.labels = builder.labels;
        this.tierOffset = builder.tierOffset;
    }

    /** The values machines of this kind take, unless a spec gives its own. Empty for kinds each machine ranges. */
    @Nullable
    public ModifierRange getRange() {
        return range;
    }

    @Nonnull
    public String getName() {
        return StatCollector.translateToLocal(nameKey);
    }

    /** In the tooltip tier colour. */
    @Nonnull
    public String getTierText() {
        return TooltipHelper.tierText(getName());
    }

    /** Such as "LuV", or the number where the kind has no labels. */
    @Nonnull
    public String label(long value) {
        return labels == null ? String.valueOf(value) : StatCollector.translateToLocal(labels.apply(value));
    }

    @Override
    public String toString() {
        return id;
    }

    /** Complete only once the machine classes, which register the kinds only they use, are loaded. */
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
    public static Builder<IntKind> ofInt(@Nonnull String id) {
        return new Builder<>(id, IntKind::new);
    }

    /** @param id Namespaced by mod, such as {@code "gregtech:coil"} */
    @Nonnull
    public static Builder<LongKind> ofLong(@Nonnull String id) {
        return new Builder<>(id, LongKind::new);
    }

    /** Numbered by ordinal, labelled by constant name, ranging over the constants. -1 means none. */
    @Nonnull
    public static <E extends Enum<E>> Builder<IntKind> ofEnum(@Nonnull String id, @Nonnull E[] values) {
        return ofInt(id).range(0, values.length - 1)
            .labels(index -> index >= 0 && index < values.length ? values[(int) index].name() : "None");
    }

    public static final class Builder<K extends ModifierKind> {

        private final String id;
        private final Function<Builder<K>, K> create;
        private String nameKey;
        private Source source = Source.STRUCTURE;
        private boolean ordered;
        private boolean hasRange;
        private long min;
        private long max;
        private LongFunction<String> labels;
        private int tierOffset;

        private Builder(String id, Function<Builder<K>, K> create) {
            this.id = id;
            this.create = create;
            this.nameKey = id;
        }

        /** A translation key, or the name itself. */
        public Builder<K> name(@Nonnull String nameKey) {
            this.nameKey = nameKey;
            return this;
        }

        /** {@link Source#STRUCTURE} unless set. */
        public Builder<K> source(@Nonnull Source source) {
            this.source = source;
            return this;
        }

        public Builder<K> ordered() {
            this.ordered = true;
            return this;
        }

        /** The values every machine of this kind takes. */
        public Builder<K> range(long min, long max) {
            if (min > max) throw new IllegalArgumentException(id + ": min " + min + " > max " + max);
            this.hasRange = true;
            this.min = min;
            this.max = max;
            return this;
        }

        /** Added to a value where per-tier terms count tiers: 1 for coils, whose tier 0 is Cupronickel. */
        public Builder<K> tierOffset(int tierOffset) {
            this.tierOffset = tierOffset;
            return this;
        }

        public Builder<K> labels(@Nonnull LongFunction<String> labels) {
            this.labels = labels;
            return this;
        }

        /** Names or translation keys, from {@code first} up. */
        public Builder<K> labels(long first, @Nonnull String... labels) {
            return labels(value -> {
                long index = value - first;
                return index >= 0 && index < labels.length ? labels[(int) index] : String.valueOf(value);
            });
        }

        /** @throws IllegalStateException if a kind with this id is registered already */
        @Nonnull
        public K register() {
            K kind = create.apply(this);
            if (REGISTRY.putIfAbsent(id, kind) != null) {
                throw new IllegalStateException("modifier kind " + id + " is registered already");
            }
            return kind;
        }
    }
}
