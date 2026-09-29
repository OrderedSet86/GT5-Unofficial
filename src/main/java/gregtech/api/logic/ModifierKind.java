package gregtech.api.logic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.util.StatCollector;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.util.tooltip.TooltipHelper;

/**
 * A value a {@link ProcessingSpec} reads, such as a coil tier, an item or momentum. Shared kinds are the constants
 * here; a kind only one machine has is registered in its class.
 *
 * @param <T> {@link Integer} for tiers and counts, {@link Long} for amounts that outgrow an int
 */
public final class ModifierKind<T extends Number & Comparable<T>> {

    /** Tells a planner how to ask for the value. */
    public enum Source {
        ENERGY,
        STRUCTURE,
        /** Such as an upgrade chip. */
        ITEM,
        /** Built up while running, such as momentum. */
        RUNTIME
    }

    private static final Map<String, ModifierKind<?>> REGISTRY = new LinkedHashMap<>();

    public static final ModifierKind<Integer> VOLTAGE = ofInt("gregtech:voltage").name("GT5U.MBTT.Tiers.Voltage")
        .source(Source.ENERGY)
        .ordered()
        .labels(tier -> GTValues.VN[tier])
        .register();
    /** {@link HeatingCoilLevel#getTier()}. */
    public static final ModifierKind<Integer> COIL = ofInt("gregtech:coil").name("GT5U.MBTT.Tiers.Coil")
        .ordered()
        .labels(
            tier -> HeatingCoilLevel.getFromTier(tier.byteValue())
                .getName())
        .register();
    /** The voltage tier of the solenoids. */
    public static final ModifierKind<Integer> SOLENOID = ofInt("gregtech:solenoid").name("GT5U.MBTT.Tiers.Solenoid")
        .ordered()
        .labels(tier -> GTValues.VN[tier])
        .register();
    /** 1 for the lowest item pipe casing. */
    public static final ModifierKind<Integer> ITEM_PIPE_CASING = ofInt("gregtech:item_pipe_casing")
        .name("GT5U.MBTT.Tiers.ItemPipe")
        .ordered()
        .register();
    /** 1 for the lowest pipe casing. */
    public static final ModifierKind<Integer> PIPE_CASING = ofInt("gregtech:pipe_casing")
        .name("GT5U.MBTT.Tiers.FluidPipe")
        .ordered()
        .register();
    /** The count of repeated slices or layers. */
    public static final ModifierKind<Integer> LENGTH = ofInt("gregtech:length").name("GT5U.MBTT.Tiers.Length")
        .ordered()
        .register();
    /** The voltage tier of the glass. */
    public static final ModifierKind<Integer> GLASS = ofInt("gregtech:glass").name("GT5U.MBTT.Tiers.Glass")
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
    private final Function<T, String> labels;

    private ModifierKind(Builder<T> b) {
        this.id = b.id;
        this.nameKey = b.nameKey;
        this.source = b.source;
        this.ordered = b.ordered;
        this.labels = b.labels;
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

    /** Such as "LuV"; the number itself if the kind has no labels. */
    @Nonnull
    public String label(@Nonnull T value) {
        return labels == null ? String.valueOf(value) : StatCollector.translateToLocal(labels.apply(value));
    }

    @Override
    public String toString() {
        return id;
    }

    /** Complete only once the machine classes, which register their own kinds, are loaded. */
    @Nonnull
    public static List<ModifierKind<?>> all() {
        return Collections.unmodifiableList(new ArrayList<>(REGISTRY.values()));
    }

    @Nullable
    public static ModifierKind<?> byId(@Nonnull String id) {
        return REGISTRY.get(id);
    }

    /** @param id Namespaced by mod, such as {@code "gregtech:coil"} */
    @Nonnull
    public static Builder<Integer> ofInt(@Nonnull String id) {
        return new Builder<>(id);
    }

    /** @param id Namespaced by mod, such as {@code "gregtech:coil"} */
    @Nonnull
    public static Builder<Long> ofLong(@Nonnull String id) {
        return new Builder<>(id);
    }

    /** Numbered by ordinal, labelled by constant name. */
    @Nonnull
    public static <E extends Enum<E>> Builder<Integer> ofEnum(@Nonnull String id, @Nonnull E[] values) {
        return ofInt(id).labels(index -> index >= 0 && index < values.length ? values[index].name() : "None");
    }

    public static final class Builder<T extends Number & Comparable<T>> {

        private final String id;
        private String nameKey;
        private Source source = Source.STRUCTURE;
        private boolean ordered;
        private Function<T, String> labels;

        private Builder(String id) {
            this.id = id;
            this.nameKey = id;
        }

        /** A translation key, or the name itself. */
        public Builder<T> name(@Nonnull String nameKey) {
            this.nameKey = nameKey;
            return this;
        }

        /** {@link Source#STRUCTURE} unless set. */
        public Builder<T> source(@Nonnull Source source) {
            this.source = source;
            return this;
        }

        public Builder<T> ordered() {
            this.ordered = true;
            return this;
        }

        public Builder<T> labels(@Nonnull Function<T, String> labels) {
            this.labels = labels;
            return this;
        }

        /** Names or translation keys, from {@code first} up. */
        public Builder<T> labels(int first, @Nonnull String... labels) {
            return labels(value -> {
                long index = value.longValue() - first;
                return index >= 0 && index < labels.length ? labels[(int) index] : String.valueOf(value);
            });
        }

        /** @throws IllegalStateException if a kind with this id is registered already */
        @Nonnull
        public ModifierKind<T> register() {
            ModifierKind<T> kind = new ModifierKind<>(this);
            if (REGISTRY.putIfAbsent(id, kind) != null) {
                throw new IllegalStateException("modifier kind " + id + " is registered already");
            }
            return kind;
        }
    }
}
