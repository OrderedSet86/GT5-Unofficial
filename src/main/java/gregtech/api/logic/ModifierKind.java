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
import gregtech.api.util.GTStructureUtility;
import gregtech.api.util.tooltip.TooltipHelper;

/**
 * A value a {@link ProcessingSpec} reads, such as a coil tier, an item count or momentum. Shared kinds are the
 * constants here. A kind only one machine has is registered in that machine's class.
 * <p>
 * The class is sealed over the value type. A consumer's switch over the kinds with no default arm then stops compiling
 * when a new value type is added.
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

    public static final class IntKind extends ModifierKind {

        private IntKind(Builder<IntKind> builder) {
            super(builder);
        }

        /** The tier that per-tier terms and their tooltips use: {@code value} plus the kind's tier offset. */
        public int countedTier(int value) {
            return value + super.tierOffset;
        }
    }

    /** Amounts that outgrow an int, such as stored fluid. */
    public static final class LongKind extends ModifierKind {

        private LongKind(Builder<LongKind> builder) {
            super(builder);
        }
    }

    /** The values a machine takes for a kind, both ends included. */
    public record Range(long min, long max) {

        public Range {
            if (min > max) throw new IllegalArgumentException("min " + min + " > max " + max);
        }

        public boolean contains(long value) {
            return value >= min && value <= max;
        }
    }

    // register() adds kinds while the machine classes load
    private static final Map<String, ModifierKind> REGISTRY = new LinkedHashMap<>();

    /** The voltage tier of the energy hatches. {@link ProcessingInputs.Builder#value} rejects this kind. */
    public static final IntKind VOLTAGE = ofInt("gregtech:voltage").name("GT5U.MBTT.Tiers.Voltage")
        .source(Source.ENERGY)
        .ordered()
        .labels(tier -> GTValues.VN[(int) tier])
        .register();
    /** The coil's heating coil tier: 0 for Cupronickel. */
    public static final IntKind COIL = ofInt("gregtech:coil").name("GT5U.MBTT.Tiers.Coil")
        .ordered()
        .range(0, HeatingCoilLevel.getMaxTier())
        .tierOffset(1)
        .labels(
            tier -> HeatingCoilLevel.getFromTier((byte) tier)
                .getName())
        .register();
    /** 1 for the lowest item pipe casing. */
    public static final IntKind ITEM_PIPE_CASING = ofInt("gregtech:item_pipe_casing").name("GT5U.MBTT.Tiers.ItemPipe")
        .ordered()
        .range(1, GTStructureUtility.ITEM_PIPE_CASING_TIERS)
        .register();
    /** {@link GTStructureUtility#pipeCasing}: 1 for the lowest pipe casing. */
    public static final IntKind PIPE_CASING = ofInt("gregtech:pipe_casing").name("GT5U.MBTT.Tiers.FluidPipe")
        .ordered()
        .range(1, GTStructureUtility.PIPE_CASING_TIERS)
        .labels(
            tier -> GTStructureUtility.pipeCasing((int) tier)
                .get(1)
                .getDisplayName())
        .register();
    /** The count of repeated slices or layers. The range is in each machine's spec. */
    public static final IntKind LENGTH = ofInt("gregtech:length").name("GT5U.MBTT.Tiers.Length")
        .ordered()
        .register();

    @Nonnull
    public final String id;
    @Nonnull
    public final String nameKey;
    @Nonnull
    public final Source source;
    /** Machines are better at higher values. A planner may start at the highest. */
    public final boolean ordered;
    @Nullable
    private final Range range;
    @Nullable
    private final LongFunction<String> labels;
    private final int tierOffset;

    private ModifierKind(Builder<?> builder) {
        this.id = builder.id;
        this.nameKey = builder.nameKey;
        this.source = builder.source;
        this.ordered = builder.ordered;
        this.range = builder.hasRange ? new Range(builder.min, builder.max) : null;
        this.labels = builder.labels;
        this.tierOffset = builder.tierOffset;
    }

    /**
     * The values machines of this kind take, or null for a kind ranged per machine. A spec's
     * {@link ProcessingSpec.Builder#range} replaces this range.
     */
    @Nullable
    public Range getRange() {
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

    /** Complete only after every machine class has loaded and registered its kinds. */
    @Nonnull
    public static List<ModifierKind> all() {
        return Collections.unmodifiableList(new ArrayList<>(REGISTRY.values()));
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

        /** Added to a value in per-tier terms: 1 for coils, whose tier 0 is Cupronickel. */
        private Builder<K> tierOffset(int tierOffset) {
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
