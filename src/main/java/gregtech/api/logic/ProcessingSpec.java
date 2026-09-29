package gregtech.api.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.OverclockCalculator;
import gregtech.api.util.tooltip.TooltipHelper;

/**
 * What a multiblock does to its recipes, as a function of its energy hatch tier, mode and {@link Modifier}s.
 * {@link MTEMultiBlockBase} applies it to the machine's {@link ProcessingLogic} and tooltip; planners evaluate it
 * without a world.
 * <p>
 * Keep one per machine class in a static field. Numbers a spec leaves unset stay in the machine's own code. Numbers it
 * sets must reach the tooltip: terms write their own line, plain functions need {@link Builder#customTooltip} or {@link
 * Builder#noTooltip}.
 */
public final class ProcessingSpec {

    /**
     * @param voltageTier As {@link gregtech.api.util.GTUtility#getTier} numbers it
     * @param amperage    1 with a single energy hatch
     */
    public record Inputs(int voltageTier, long amperage, int mode, @Nonnull Map<ModifierKind, Integer> values) {

        /**
         * {@link ModifierKind#VOLTAGE} reads {@link #voltageTier}.
         *
         * @throws IllegalArgumentException if no value was given for the kind
         */
        public int value(@Nonnull ModifierKind kind) {
            if (kind == ModifierKind.VOLTAGE) return voltageTier;
            Integer value = values.get(kind);
            if (value == null) throw new IllegalArgumentException("no " + kind + " value given");
            return value;
        }

        @Nonnull
        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {

            private int voltageTier;
            private long amperage = 1;
            private int mode;
            private final Map<ModifierKind, Integer> values = new HashMap<>();

            private Builder() {}

            public Builder voltageTier(int voltageTier) {
                this.voltageTier = voltageTier;
                return this;
            }

            public Builder amperage(long amperage) {
                this.amperage = amperage;
                return this;
            }

            public Builder mode(int mode) {
                this.mode = mode;
                return this;
            }

            public Builder value(@Nonnull ModifierKind kind, int value) {
                this.values.put(kind, value);
                return this;
            }

            public Builder modifiers(@Nonnull List<Modifier> modifiers) {
                for (Modifier modifier : modifiers) value(modifier.kind, modifier.get());
                return this;
            }

            /** The best machine only for ordered kinds. */
            public Builder modifiersAtMax(@Nonnull List<Modifier> modifiers) {
                for (Modifier modifier : modifiers) value(modifier.kind, modifier.max);
                return this;
            }

            @Nonnull
            public Inputs build() {
                return new Inputs(voltageTier, amperage, mode, Collections.unmodifiableMap(new HashMap<>(values)));
            }
        }
    }

    /** Replaces every recipe's cost, as the Multi Smelter does. */
    public record RecipeOverride(long eut, int duration) {

        /** {@code RecipeOverride.eut(4).duration(128)} */
        @Nonnull
        public static WithEut eut(long eut) {
            return new WithEut(eut);
        }

        public record WithEut(long eut) {

            @Nonnull
            public RecipeOverride duration(int duration) {
                return new RecipeOverride(eut, duration);
            }
        }
    }

    public sealed interface OverclockRule {

        /** As {@link OverclockCalculator#ofNoOverclock}. */
        record None() implements OverclockRule {}

        record Ratio(double durationDivisor, double euMultiplier) implements OverclockRule {

            public boolean isPerfect() {
                return durationDivisor == 4 && euMultiplier == 4;
            }
        }
    }

    public enum HeatRule {
        /** Perfect overclocks for every 1800K the machine's heat exceeds the recipe's. */
        OVERCLOCK,
        /** 5% less EU/t for every 900K the machine's heat exceeds the recipe's. */
        DISCOUNT
    }

    public static final class Heat {

        private final ToIntFunction<Inputs> machineHeat;
        private final Set<HeatRule> rules;
        private final OptionalInt fixedRecipeHeat;

        private Heat(ToIntFunction<Inputs> machineHeat, Set<HeatRule> rules, OptionalInt fixedRecipeHeat) {
            this.machineHeat = machineHeat;
            this.rules = rules;
            this.fixedRecipeHeat = fixedRecipeHeat;
        }

        public int getMachineHeat(@Nonnull Inputs inputs) {
            return machineHeat.applyAsInt(inputs);
        }

        /** The fixed recipe heat if there is one, else the recipe's. */
        public int getRecipeHeat(@Nonnull GTRecipe recipe) {
            return fixedRecipeHeat.orElse(recipe.mSpecialValue);
        }

        @Nonnull
        public OptionalInt getFixedRecipeHeat() {
            return fixedRecipeHeat;
        }

        public boolean overclocks() {
            return rules.contains(HeatRule.OVERCLOCK);
        }

        public boolean discounts() {
            return rules.contains(HeatRule.DISCOUNT);
        }
    }

    /** In tooltip order. */
    public enum Quantity {
        PARALLEL,
        DURATION,
        EU_MODIFIER,
        /** Unlike {@link #EU_MODIFIER}, does not lower the parallels energy allows. */
        EU_MODIFIER_NOT_LIMITING_PARALLEL,
        OVERCLOCK,
        TIER_SKIPS,
        HEAT,
        RECIPE_OVERRIDE
    }

    /**
     * @param result    Unsuccessful means every number is 0
     * @param parallel  After the energy limit
     * @param euPerTick For all parallels together
     */
    public record Run(@Nonnull CheckRecipeResult result, int parallel, int overclocks, int ticks, long euPerTick) {}

    public static final ToIntFunction<Inputs> COIL_HEAT = in -> (int) HeatingCoilLevel
        .getFromTier((byte) in.value(ModifierKind.COIL))
        .getHeat();

    /** For a machine that runs a plain {@link ProcessingLogic}. */
    public static final ProcessingSpec STANDARD = builder().build();

    private record ParallelTerm(ToIntFunction<Inputs> value, @Nullable Consumer<MultiblockTooltipBuilder> tooltip) {}

    private record Term(ToDoubleFunction<Inputs> value, @Nullable Consumer<MultiblockTooltipBuilder> tooltip) {}

    /** @param name Heads the tooltip lines, given the spec's modes; null leaves them out */
    private record Variant(Predicate<Inputs> appliesTo, Function<List<MachineMode>, String> name,
        @Nullable Integer mode, ProcessingSpec terms) {}

    private static final Set<Quantity> VARIABLE = Collections.unmodifiableSet(
        EnumSet.of(
            Quantity.PARALLEL,
            Quantity.DURATION,
            Quantity.EU_MODIFIER,
            Quantity.EU_MODIFIER_NOT_LIMITING_PARALLEL));

    private final List<ParallelTerm> parallel;
    @Nullable
    private final Term duration;
    @Nullable
    private final Term euModifier;
    @Nullable
    private final Term euModifierNotLimitingParallel;
    @Nullable
    private final OverclockRule overclock;
    private final OptionalInt maxTierSkips;
    @Nullable
    private final Heat heat;
    @Nullable
    private final RecipeOverride recipeOverride;
    private final Map<Quantity, Consumer<MultiblockTooltipBuilder>> customTooltips;
    private final Set<Quantity> noTooltip;
    private final Set<Quantity> alsoCustom;
    private final List<MachineMode> modes;
    private final List<Variant> variants;

    private ProcessingSpec(Builder b) {
        this.parallel = new ArrayList<>(b.parallel);
        this.duration = b.duration;
        this.euModifier = b.euModifier;
        this.euModifierNotLimitingParallel = b.euModifierNotLimitingParallel;
        this.overclock = b.overclock;
        this.maxTierSkips = b.maxTierSkips;
        this.heat = b.heatFunction == null ? null
            : new Heat(b.heatFunction, Collections.unmodifiableSet(EnumSet.copyOf(b.heatRules)), b.recipeHeat);
        this.recipeOverride = b.recipeOverride;
        this.customTooltips = new EnumMap<>(b.customTooltips);
        this.noTooltip = b.noTooltip.clone();
        this.alsoCustom = b.alsoCustom.clone();
        this.modes = b.modes;
        this.variants = new ArrayList<>(b.variants);
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** At least 1. */
    public int getMaxParallel(@Nonnull Inputs inputs) {
        List<ParallelTerm> terms = parallel;
        for (Variant variant : variants) {
            if (!variant.terms.parallel.isEmpty() && variant.appliesTo.test(inputs)) {
                terms = variant.terms.parallel;
                break;
            }
        }
        int sum = 0;
        for (ParallelTerm term : terms) {
            sum += term.value.applyAsInt(inputs);
        }
        return Math.max(1, sum);
    }

    /** 0.5 halves recipe time. */
    public double getDurationMultiplier(@Nonnull Inputs inputs) {
        return value(inputs, spec -> spec.duration);
    }

    public double getEuModifier(@Nonnull Inputs inputs) {
        return value(inputs, spec -> spec.euModifier);
    }

    public double getEuModifierNotLimitingParallel(@Nonnull Inputs inputs) {
        return value(inputs, spec -> spec.euModifierNotLimitingParallel);
    }

    /** The first matching variant's term, else the spec's own, else 1. */
    private double value(Inputs inputs, Function<ProcessingSpec, Term> quantity) {
        Term term = quantity.apply(this);
        for (Variant variant : variants) {
            Term variantTerm = quantity.apply(variant.terms);
            if (variantTerm != null && variant.appliesTo.test(inputs)) {
                term = variantTerm;
                break;
            }
        }
        return term == null ? 1 : term.value.applyAsDouble(inputs);
    }

    @Nonnull
    public Optional<OverclockRule> getOverclock() {
        return Optional.ofNullable(overclock);
    }

    public boolean isNoOverclock() {
        return overclock instanceof OverclockRule.None;
    }

    public boolean isPerfectOverclock() {
        return overclock instanceof OverclockRule.Ratio ratio && ratio.isPerfect();
    }

    /** {@link Integer#MAX_VALUE} for unlimited. */
    @Nonnull
    public OptionalInt getMaxTierSkips() {
        return maxTierSkips;
    }

    @Nonnull
    public Optional<Heat> getHeat() {
        return Optional.ofNullable(heat);
    }

    @Nonnull
    public Optional<RecipeOverride> getRecipeOverride() {
        return Optional.ofNullable(recipeOverride);
    }

    public boolean sets(@Nonnull Quantity quantity) {
        if (setsItself(quantity)) return true;
        for (Variant variant : variants) if (variant.terms.sets(quantity)) return true;
        return false;
    }

    private boolean setsItself(Quantity quantity) {
        return switch (quantity) {
            case PARALLEL -> !parallel.isEmpty();
            case DURATION -> duration != null;
            case EU_MODIFIER -> euModifier != null;
            case EU_MODIFIER_NOT_LIMITING_PARALLEL -> euModifierNotLimitingParallel != null;
            case OVERCLOCK -> overclock != null;
            case TIER_SKIPS -> maxTierSkips.isPresent();
            case HEAT -> heat != null;
            case RECIPE_OVERRIDE -> recipeOverride != null;
        };
    }

    /**
     * False if the machine's own code also changes numbers ({@link Builder#alsoCustom}); planners then ask the machine.
     */
    public boolean isComplete() {
        return alsoCustom.isEmpty();
    }

    @Nonnull
    public Set<Quantity> getAlsoCustom() {
        return Collections.unmodifiableSet(alsoCustom);
    }

    /** As {@link ProcessingLogic} would run the recipe. */
    @Nonnull
    public Run calculate(@Nonnull GTRecipe recipe, @Nonnull Inputs inputs) {
        return new ProcessingLogic().setAvailableVoltage(GTValues.V[inputs.voltageTier()])
            .setAvailableAmperage(inputs.amperage())
            .setAmperageOC(true)
            .applySpec(this, () -> inputs)
            .calculateForInspection(recipe);
    }

    /**
     * Writes the lines in {@link Quantity} order, then each variant's under its name.
     *
     * @return The quantities accounted for, {@link Builder#noTooltip} ones included
     */
    @Nonnull
    public Set<Quantity> describe(@Nonnull MultiblockTooltipBuilder tt) {
        EnumSet<Quantity> overridden = overridden();
        Set<Quantity> ownDescribed = describe(tt, EnumSet.noneOf(Quantity.class));
        List<Set<Quantity>> variantDescribed = new ArrayList<>();
        for (Variant variant : variants) {
            MultiblockTooltipBuilder lines = new MultiblockTooltipBuilder();
            variantDescribed.add(variant.terms.describe(lines, overridden));
            String name = variant.name.apply(modes);
            if (name != null && tt.markSpecLinesWritten(variant)) {
                tt.addLinesFrom(
                    EnumChatFormatting.WHITE + StatCollector.translateToLocal(name) + EnumChatFormatting.GRAY + ": ",
                    lines);
            }
        }

        EnumSet<Quantity> described = EnumSet.noneOf(Quantity.class);
        for (Quantity quantity : Quantity.values()) {
            if (overridden.contains(quantity)) {
                described.add(quantity);
                continue;
            }
            if (!sets(quantity)) continue;
            boolean all = !setsItself(quantity) || ownDescribed.contains(quantity);
            for (int i = 0; i < variants.size(); i++) {
                if (variants.get(i).terms.sets(quantity) && !variantDescribed.get(i)
                    .contains(quantity)) all = false;
            }
            if (all) described.add(quantity);
        }
        return described;
    }

    /**
     * Writes the {@link Builder#inMode} lines for {@code mode} without the mode's name, for a tooltip section about
     * that
     * mode. A later {@link #describe} leaves them out.
     */
    public void describeMode(@Nonnull MultiblockTooltipBuilder tt, int mode) {
        boolean any = false;
        for (Variant variant : variants) {
            if (variant.mode == null || variant.mode != mode) continue;
            any = true;
            if (tt.markSpecLinesWritten(variant)) variant.terms.describe(tt, overridden());
        }
        if (!any) throw new IllegalArgumentException("no inMode terms for mode " + mode);
    }

    private EnumSet<Quantity> overridden() {
        EnumSet<Quantity> overridden = EnumSet.copyOf(noTooltip);
        overridden.addAll(customTooltips.keySet());
        return overridden;
    }

    private EnumSet<Quantity> describe(MultiblockTooltipBuilder tt, Set<Quantity> skip) {
        EnumSet<Quantity> described = EnumSet.noneOf(Quantity.class);
        for (Quantity quantity : Quantity.values()) {
            if (skip.contains(quantity)) continue;
            if (noTooltip.contains(quantity)) {
                described.add(quantity);
                continue;
            }
            Consumer<MultiblockTooltipBuilder> custom = customTooltips.get(quantity);
            if (custom != null) {
                custom.accept(tt);
                described.add(quantity);
                continue;
            }
            if (describeItself(quantity, tt)) described.add(quantity);
        }
        return described;
    }

    @Nonnull
    public Set<Quantity> getUndescribed() {
        EnumSet<Quantity> undescribed = EnumSet.noneOf(Quantity.class);
        for (Quantity quantity : Quantity.values()) if (sets(quantity)) undescribed.add(quantity);
        undescribed.removeAll(describe(new MultiblockTooltipBuilder()));
        return undescribed;
    }

    private boolean describeItself(Quantity quantity, MultiblockTooltipBuilder tt) {
        switch (quantity) {
            case PARALLEL -> {
                boolean all = !parallel.isEmpty();
                for (ParallelTerm term : parallel) {
                    if (term.tooltip == null) all = false;
                    else term.tooltip.accept(tt);
                }
                return all;
            }
            case DURATION -> {
                return describe(duration, tt);
            }
            case EU_MODIFIER -> {
                return describe(euModifier, tt);
            }
            case EU_MODIFIER_NOT_LIMITING_PARALLEL -> {
                return describe(euModifierNotLimitingParallel, tt);
            }
            case OVERCLOCK -> {
                if (!isPerfectOverclock()) return false;
                tt.addPerfectOCInfo();
                return true;
            }
            case TIER_SKIPS -> {
                if (maxTierSkips.isEmpty()) return false;
                int skips = maxTierSkips.getAsInt();
                if (skips == Integer.MAX_VALUE) tt.addUnlimitedTierSkips();
                else if (skips > 1) tt.addMaxTierSkips(skips);
                else return false;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean describe(@Nullable Term term, MultiblockTooltipBuilder tt) {
        if (term == null || term.tooltip == null) return false;
        term.tooltip.accept(tt);
        return true;
    }

    /** Tooltips count coil tiers from 1, for Cupronickel. */
    private static int shownTier(Inputs inputs, ModifierKind kind) {
        return kind == ModifierKind.COIL ? inputs.value(kind) + 1 : inputs.value(kind);
    }

    public static final class Builder {

        private final List<ParallelTerm> parallel = new ArrayList<>();
        private Term duration;
        private Term euModifier;
        private Term euModifierNotLimitingParallel;
        private OverclockRule overclock;
        private OptionalInt maxTierSkips = OptionalInt.empty();
        private ToIntFunction<Inputs> heatFunction;
        private final EnumSet<HeatRule> heatRules = EnumSet.noneOf(HeatRule.class);
        private OptionalInt recipeHeat = OptionalInt.empty();
        private RecipeOverride recipeOverride;
        private final EnumMap<Quantity, Consumer<MultiblockTooltipBuilder>> customTooltips = new EnumMap<>(
            Quantity.class);
        private final EnumSet<Quantity> noTooltip = EnumSet.noneOf(Quantity.class);
        private final EnumSet<Quantity> alsoCustom = EnumSet.noneOf(Quantity.class);
        private List<MachineMode> modes;
        private final List<Variant> variants = new ArrayList<>();

        private Builder() {}

        /** Parallel terms add up. */
        public Builder parallel(int parallel) {
            return parallelTerm(in -> parallel, tt -> tt.addStaticParallelInfo(parallel));
        }

        /** Read on every use, such as a config value. */
        public Builder parallel(@Nonnull IntSupplier parallel) {
            return parallelTerm(in -> parallel.getAsInt(), tt -> tt.addStaticParallelInfo(parallel.getAsInt()));
        }

        /** {@code parallel} times the product of the kinds' tiers. */
        public Builder parallelPerTier(int parallel, @Nonnull ModifierKind... kinds) {
            if (kinds.length == 0) throw new IllegalArgumentException("parallelPerTier needs a tier");
            Consumer<MultiblockTooltipBuilder> tooltip = kinds.length == 1
                ? tt -> tt.addDynamicParallelInfo(parallel, kinds[0])
                : tt -> tt.addInfo(
                    TooltipHelper.parallelText(
                        Arrays.stream(kinds)
                            .map(kind -> kind.getName() + " Tier")
                            .collect(Collectors.joining(" * ")) + " * "
                            + parallel)
                        + " Parallels");
            return parallelTerm(in -> {
                int product = parallel;
                for (ModifierKind kind : kinds) product *= shownTier(in, kind);
                return product;
            }, tooltip);
        }

        /** Counts ULV as LV. */
        public Builder parallelPerVoltageTier(int parallel) {
            return parallelTerm(
                in -> parallel * Math.max(1, in.voltageTier()),
                tt -> tt.addVoltageParallelInfo(parallel));
        }

        public Builder parallel(@Nonnull ToIntFunction<Inputs> parallel) {
            return parallelTerm(parallel, null);
        }

        private Builder parallelTerm(ToIntFunction<Inputs> value, @Nullable Consumer<MultiblockTooltipBuilder> tt) {
            this.parallel.add(new ParallelTerm(value, tt));
            return this;
        }

        /** 2.5 is 250% speed: recipes take 1 / 2.5 of the time. */
        public Builder speed(double speed) {
            this.duration = new Term(in -> 1 / speed, tt -> tt.addStaticSpeedInfo((float) speed));
            return this;
        }

        public Builder speed(@Nonnull ToDoubleFunction<Inputs> speed) {
            this.duration = new Term(in -> 1 / speed.applyAsDouble(in), null);
            return this;
        }

        /** {@code base} plus {@code perTier} per tier of {@code kind}: 1 and 1 is 200% at the first tier. */
        public Builder speedPerTier(double base, double perTier, @Nonnull ModifierKind kind) {
            this.duration = new Term(
                in -> 1 / (base + perTier * shownTier(in, kind)),
                tt -> tt.addSpeedPerTierInfo((float) base, (float) perTier, kind));
            return this;
        }

        /** {@code first} at the first tier, plus {@code perTier} per further tier. */
        public Builder speedPerTierBeyondFirst(double first, double perTier, @Nonnull ModifierKind kind) {
            this.duration = new Term(
                in -> 1.0 / (first + perTier * (shownTier(in, kind) - 1)),
                tt -> tt.addSpeedPerTierBeyondFirstInfo((float) first, (float) perTier, kind));
            return this;
        }

        /** As {@link ProcessingLogic#setSpeedBonus} takes it: 0.5 halves recipe time. */
        public Builder durationMultiplier(@Nonnull ToDoubleFunction<Inputs> durationMultiplier) {
            this.duration = new Term(durationMultiplier, null);
            return this;
        }

        public Builder euModifier(double euModifier) {
            this.euModifier = new Term(in -> euModifier, tt -> tt.addStaticEuEffInfo((float) euModifier));
            return this;
        }

        public Builder euModifier(@Nonnull ToDoubleFunction<Inputs> euModifier) {
            this.euModifier = new Term(euModifier, null);
            return this;
        }

        /** Follow with {@link #maxEuDiscount} for a cap. */
        public Builder euDiscountPerTier(double discount, @Nonnull ModifierKind kind) {
            this.euModifier = new Term(
                in -> 1 - discount * shownTier(in, kind),
                tt -> tt.addDynamicEuEffInfo((float) discount, kind));
            return this;
        }

        /** {@code euModifier} at the first tier, times {@code factor} per further tier. */
        public Builder euModifierPerTierBeyondFirst(double euModifier, double factor, @Nonnull ModifierKind kind) {
            this.euModifier = new Term(
                in -> euModifier * GTUtility.powInt(factor, shownTier(in, kind) - 1),
                tt -> tt.addStaticEuEffInfo((float) euModifier)
                    .addEuMultiplierBeyondFirstInfo((float) factor, kind));
            return this;
        }

        public Builder maxEuDiscount(double maxDiscount) {
            Term perTier = this.euModifier;
            if (perTier == null || perTier.tooltip == null) {
                throw new IllegalStateException("maxEuDiscount follows euDiscountPerTier");
            }
            this.euModifier = new Term(
                in -> Math.max(perTier.value.applyAsDouble(in), 1 - maxDiscount),
                perTier.tooltip.andThen(
                    tt -> tt.addInfo("Maximum of " + TooltipHelper.effText((float) maxDiscount) + " EU discount")));
            return this;
        }

        public Builder euModifierNotLimitingParallel(@Nonnull ToDoubleFunction<Inputs> euModifier) {
            this.euModifierNotLimitingParallel = new Term(euModifier, null);
            return this;
        }

        /** As {@link OverclockCalculator#ofNoOverclock}. */
        public Builder noOverclock() {
            this.overclock = new OverclockRule.None();
            return this;
        }

        public Builder perfectOverclock() {
            return overclock(4, 4);
        }

        public Builder overclock(double durationDivisor, double euMultiplier) {
            this.overclock = new OverclockRule.Ratio(durationDivisor, euMultiplier);
            return this;
        }

        /** The tooltip only states values above 1. */
        public Builder maxTierSkips(int tierSkips) {
            this.maxTierSkips = OptionalInt.of(tierSkips);
            return this;
        }

        public Builder unlimitedTierSkips() {
            return maxTierSkips(Integer.MAX_VALUE);
        }

        public Builder heat(@Nonnull ToIntFunction<Inputs> machineHeat, @Nonnull HeatRule... rules) {
            this.heatFunction = machineHeat;
            Collections.addAll(this.heatRules, rules);
            return this;
        }

        /** Overclocks against this heat instead of the recipe's. */
        public Builder recipeHeat(int heat) {
            this.recipeHeat = OptionalInt.of(heat);
            return this;
        }

        public Builder recipeOverride(@Nonnull RecipeOverride recipeOverride) {
            this.recipeOverride = recipeOverride;
            return this;
        }

        public Builder customTooltip(@Nonnull Quantity quantity, @Nonnull Consumer<MultiblockTooltipBuilder> lines) {
            this.customTooltips.put(quantity, lines);
            return this;
        }

        /** For numbers the tooltip covers elsewhere, or not at all. */
        public Builder noTooltip(@Nonnull Quantity... quantities) {
            Collections.addAll(this.noTooltip, quantities);
            return this;
        }

        /** For numbers the machine's own code also changes. */
        public Builder alsoCustom(@Nonnull Quantity... quantities) {
            Collections.addAll(this.alsoCustom, quantities);
            return this;
        }

        /** Names the {@link #inMode} lines. */
        public Builder modes(@Nonnull List<MachineMode> modes) {
            this.modes = Objects.requireNonNull(modes, "declare the modes before the spec");
            return this;
        }

        /** Replaces the spec's terms in one mode. The lines are headed by the mode's name. */
        public Builder inMode(int mode, @Nonnull Consumer<Builder> terms) {
            return variant(
                in -> in.mode() == mode,
                modes -> modes != null && mode < modes.size() ? modes.get(mode)
                    .nameKey() : null,
                mode,
                terms);
        }

        /** Replaces the spec's terms at one value. The lines are headed by {@link ModifierKind#label}. */
        public Builder whenTier(@Nonnull ModifierKind kind, int value, @Nonnull Consumer<Builder> terms) {
            return variant(in -> in.value(kind) == value, modes -> kind.label(value), null, terms);
        }

        private Builder variant(Predicate<Inputs> appliesTo, Function<List<MachineMode>, String> name,
            @Nullable Integer mode, Consumer<Builder> terms) {
            Builder variant = new Builder();
            terms.accept(variant);
            ProcessingSpec spec = variant.build();
            for (Quantity quantity : Quantity.values()) {
                if (spec.sets(quantity) && !VARIABLE.contains(quantity)) {
                    throw new IllegalArgumentException(quantity + " cannot differ by mode or tier");
                }
            }
            if (!variant.alsoCustom.isEmpty() || !variant.variants.isEmpty() || variant.modes != null) {
                throw new IllegalArgumentException("a mode or tier takes terms and their tooltips only");
            }
            this.variants.add(new Variant(appliesTo, name, mode, spec));
            return this;
        }

        @Nonnull
        public ProcessingSpec build() {
            if (!heatRules.isEmpty() && heatFunction == null)
                throw new IllegalStateException("heat rules without heat");
            if (recipeHeat.isPresent() && heatFunction == null) {
                throw new IllegalStateException("recipe heat without machine heat");
            }
            return new ProcessingSpec(this);
        }
    }
}
