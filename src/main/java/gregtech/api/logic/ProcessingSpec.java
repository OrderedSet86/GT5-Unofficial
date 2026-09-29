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
 * What a multiblock does to the recipes it runs, as functions of its energy hatch tier, its mode and its
 * {@link Modifier}s. A machine that returns one from {@link MTEMultiBlockBase#getProcessingSpec()} runs its
 * {@link ProcessingLogic} from it and describes it in its tooltip with
 * {@link MultiblockTooltipBuilder#addProcessingSpecInfo}, so the numbers are written once. External tools such as
 * factory planners evaluate it with their own {@link Inputs}, without a world.
 * <p>
 * Keep one per machine class in a static field. Anything a spec leaves unset is the machine's own business: its
 * {@link ProcessingLogic} keeps whatever the machine sets, and a factory planner reads the plain default.
 * <p>
 * Every number a spec sets appears in the tooltip. The terms such as {@link Builder#parallelPerTier} write their own
 * line; a number given as a plain function needs {@link Builder#customTooltip} or {@link Builder#noTooltip}.
 * <p>
 * Numbers that differ by machine mode or by a modifier's value are declared with {@link Builder#inMode} and
 * {@link Builder#whenTier}, whose lines the tooltip heads with the mode's name or the value's label.
 */
public final class ProcessingSpec {

    /**
     * What a spec reads.
     *
     * @param voltageTier The energy hatch tier, as {@link gregtech.api.util.GTUtility#getTier} numbers it
     * @param amperage    The amperage the machine runs recipes with: 1 with a single energy hatch
     * @param mode        The machine mode, as {@link MTEMultiBlockBase#getMachineMode()} numbers it
     * @param values      The value of each {@link Modifier}, by kind
     */
    public record Inputs(int voltageTier, long amperage, int mode, @Nonnull Map<ModifierKind, Integer> values) {

        /**
         * {@link ModifierKind#VOLTAGE} is the energy hatch tier; any other kind is a modifier's value.
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

            /** 1 unless set, as for a machine with a single energy hatch. */
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

            /** Each modifier's current value, as the machine has it. */
            public Builder modifiers(@Nonnull List<Modifier> modifiers) {
                for (Modifier modifier : modifiers) value(modifier.kind, modifier.get());
                return this;
            }

            /** Each modifier at its maximum, which is the best machine for an {@link ModifierKind#ordered} kind. */
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

    /** A fixed recipe cost that replaces the recipe's own, such as the Multi Smelter's. */
    public record RecipeOverride(long eut, int duration) {

        /** Starts a cost: {@code RecipeOverride.eut(4).duration(128)}. */
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

    /** How a spec overclocks recipes, if it says. */
    public sealed interface OverclockRule {

        /** Recipes run at their own voltage and are never overclocked, as {@link OverclockCalculator#ofNoOverclock}. */
        record None() implements OverclockRule {}

        /**
         * Each overclock divides the duration by {@code durationDivisor} and multiplies EU/t by {@code euMultiplier}.
         */
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

    /** A machine's heat and what it does with it. */
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

        /** The heat a recipe is overclocked against: a fixed floor if the machine has one, else the recipe's own. */
        public int getRecipeHeat(@Nonnull GTRecipe recipe) {
            return fixedRecipeHeat.orElse(recipe.mSpecialValue);
        }

        /** Empty when recipes are overclocked against their own heat. */
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

    /** The numbers a spec may set, in the order the tooltip shows them. */
    public enum Quantity {
        PARALLEL,
        DURATION,
        EU_MODIFIER,
        /** An EU/t multiplier that, unlike {@link #EU_MODIFIER}, does not limit how many parallels energy allows. */
        EU_MODIFIER_NOT_LIMITING_PARALLEL,
        OVERCLOCK,
        TIER_SKIPS,
        HEAT,
        RECIPE_OVERRIDE
    }

    /**
     * What a machine does with one recipe.
     *
     * @param result    Whether it can run the recipe at all; the numbers are 0 when it cannot
     * @param parallel  Recipes run at once, after the energy limit
     * @param ticks     Duration of one run
     * @param euPerTick EU/t of one run, all parallels together
     */
    public record Run(@Nonnull CheckRecipeResult result, int parallel, int overclocks, int ticks, long euPerTick) {}

    /** The heat of the heating coils, as {@link HeatingCoilLevel#getHeat()}. */
    public static final ToIntFunction<Inputs> COIL_HEAT = in -> (int) HeatingCoilLevel
        .getFromTier((byte) in.value(ModifierKind.COIL))
        .getHeat();

    /** For a machine that runs recipes as a plain {@link ProcessingLogic} does. */
    public static final ProcessingSpec STANDARD = builder().build();

    private record ParallelTerm(ToIntFunction<Inputs> value, @Nullable Consumer<MultiblockTooltipBuilder> tooltip) {}

    private record Term(ToDoubleFunction<Inputs> value, @Nullable Consumer<MultiblockTooltipBuilder> tooltip) {}

    /**
     * Terms that replace the spec's own while {@code appliesTo} holds.
     *
     * @param name The translation key or text that heads the variant's tooltip lines, from the spec's modes; null
     *             for a mode the machine does not have
     */
    private record Variant(Predicate<Inputs> appliesTo, Function<List<MachineMode>, String> name,
        ProcessingSpec terms) {}

    /** The quantities {@link Builder#inMode} and {@link Builder#whenTier} can set. */
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

    /** The sum of the parallel terms, and at least 1. */
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

    /** The duration multiplier, as {@link ProcessingLogic#setSpeedBonus} takes it: 0.5 halves recipe time. */
    public double getDurationMultiplier(@Nonnull Inputs inputs) {
        return value(inputs, spec -> spec.duration);
    }

    /** The EU/t multiplier, as {@link ProcessingLogic#setEuModifier} takes it. */
    public double getEuModifier(@Nonnull Inputs inputs) {
        return value(inputs, spec -> spec.euModifier);
    }

    /** See {@link Quantity#EU_MODIFIER_NOT_LIMITING_PARALLEL}. */
    public double getEuModifierNotLimitingParallel(@Nonnull Inputs inputs) {
        return value(inputs, spec -> spec.euModifierNotLimitingParallel);
    }

    /** The first variant's term that applies, else the spec's own, else 1. */
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

    /** Empty when the machine's own overclocks apply. */
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

    /** Empty when the machine's own tier skips apply; {@link Integer#MAX_VALUE} for unlimited. */
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
     * False when the machine also changes the numbers in code of its own, so the spec alone does not give them. A
     * planner then asks the machine, as {@link MTEMultiBlockBase#createOverclockCalculatorForInspection}.
     */
    public boolean isComplete() {
        return alsoCustom.isEmpty();
    }

    @Nonnull
    public Set<Quantity> getAlsoCustom() {
        return Collections.unmodifiableSet(alsoCustom);
    }

    /**
     * What the machine does with one recipe at these inputs: the parallel it gets after the energy limit, its
     * overclocks, duration and EU/t, as {@link ProcessingLogic} works them out.
     */
    @Nonnull
    public Run calculate(@Nonnull GTRecipe recipe, @Nonnull Inputs inputs) {
        return new ProcessingLogic().setAvailableVoltage(GTValues.V[inputs.voltageTier()])
            .setAvailableAmperage(inputs.amperage())
            .setAmperageOC(true)
            .applySpec(this, () -> inputs)
            .calculateForInspection(recipe);
    }

    /**
     * Adds the tooltip lines for everything this spec sets, in {@link Quantity} order, then the lines of each
     * {@link Builder#inMode} and {@link Builder#whenTier}, headed by its name.
     *
     * @return The quantities the tooltip accounts for: a line written, or {@link Builder#noTooltip}
     */
    @Nonnull
    public Set<Quantity> describe(@Nonnull MultiblockTooltipBuilder tt) {
        EnumSet<Quantity> overridden = EnumSet.copyOf(noTooltip);
        overridden.addAll(customTooltips.keySet());
        Set<Quantity> ownDescribed = describe(tt, EnumSet.noneOf(Quantity.class));
        List<Set<Quantity>> variantDescribed = new ArrayList<>();
        for (Variant variant : variants) {
            MultiblockTooltipBuilder lines = new MultiblockTooltipBuilder();
            variantDescribed.add(variant.terms.describe(lines, overridden));
            String name = variant.name.apply(modes);
            if (name != null) {
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

    /** The lines for this spec's own terms, leaving out {@code skip}. */
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

    /** The quantities this spec sets that its tooltip does not account for. */
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

    /** A tier as tooltips count it: heating coils from 1 for Cupronickel, everything else as {@link Inputs} has it. */
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

        /** Adds a fixed parallel. Parallel terms add up. */
        public Builder parallel(int parallel) {
            return parallelTerm(in -> parallel, tt -> tt.addStaticParallelInfo(parallel));
        }

        /** Adds a fixed parallel read when used, such as from the config. */
        public Builder parallel(@Nonnull IntSupplier parallel) {
            return parallelTerm(in -> parallel.getAsInt(), tt -> tt.addStaticParallelInfo(parallel.getAsInt()));
        }

        /**
         * Adds {@code parallel} times the product of the tiers of {@code kinds}; {@link ModifierKind#VOLTAGE} is the
         * energy hatch tier.
         */
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

        /** Adds {@code parallel} per energy hatch tier, counting ULV as LV. */
        public Builder parallelPerVoltageTier(int parallel) {
            return parallelTerm(
                in -> parallel * Math.max(1, in.voltageTier()),
                tt -> tt.addVoltageParallelInfo(parallel));
        }

        /** Adds a parallel; its tooltip line is {@link #customTooltip} or {@link #noTooltip}. */
        public Builder parallel(@Nonnull ToIntFunction<Inputs> parallel) {
            return parallelTerm(parallel, null);
        }

        private Builder parallelTerm(ToIntFunction<Inputs> value, @Nullable Consumer<MultiblockTooltipBuilder> tt) {
            this.parallel.add(new ParallelTerm(value, tt));
            return this;
        }

        /** A fixed speed as the tooltip shows it: 2.5 reads as 250% and runs recipes in 1 / 2.5 of the time. */
        public Builder speed(double speed) {
            this.duration = new Term(in -> 1 / speed, tt -> tt.addStaticSpeedInfo((float) speed));
            return this;
        }

        /** A speed as the tooltip shows it; its tooltip line is {@link #customTooltip} or {@link #noTooltip}. */
        public Builder speed(@Nonnull ToDoubleFunction<Inputs> speed) {
            this.duration = new Term(in -> 1 / speed.applyAsDouble(in), null);
            return this;
        }

        /** {@code speed} times the tier of {@code kind}: 0.5 per coil tier reads as 50% per tier. */
        public Builder speedPerTier(double speed, @Nonnull ModifierKind kind) {
            this.duration = new Term(
                in -> 1 / (speed * shownTier(in, kind)),
                tt -> tt.addDynamicSpeedInfo((float) speed, kind));
            return this;
        }

        /** 100% speed plus {@code bonus} per tier of {@code kind}: 0.05 reads as +5% per tier. */
        public Builder speedBonusPerTier(double bonus, @Nonnull ModifierKind kind) {
            this.duration = new Term(
                in -> 1 / (1 + bonus * shownTier(in, kind)),
                tt -> tt.addDynamicSpeedBonusInfo((float) bonus, kind));
            return this;
        }

        /**
         * {@code speed} at the first tier of {@code kind}, plus {@code bonus} for each tier beyond it: 2.5 and 0.05
         * read
         * as 250% speed, +5% per tier beyond the first.
         */
        public Builder speedPerTierBeyondFirst(double speed, double bonus, @Nonnull ModifierKind kind) {
            this.duration = new Term(
                in -> 1.0 / (speed + bonus * (shownTier(in, kind) - 1)),
                tt -> tt.addStaticSpeedInfo((float) speed)
                    .addSpeedBonusBeyondFirstInfo((float) bonus, kind));
            return this;
        }

        /**
         * A duration multiplier, as {@link ProcessingLogic#setSpeedBonus} takes it; its tooltip line is
         * {@link #customTooltip} or {@link #noTooltip}.
         */
        public Builder durationMultiplier(@Nonnull ToDoubleFunction<Inputs> durationMultiplier) {
            this.duration = new Term(durationMultiplier, null);
            return this;
        }

        /** A fixed EU/t multiplier: 0.8 reads as 80% EU usage. */
        public Builder euModifier(double euModifier) {
            this.euModifier = new Term(in -> euModifier, tt -> tt.addStaticEuEffInfo((float) euModifier));
            return this;
        }

        /** An EU/t multiplier; its tooltip line is {@link #customTooltip} or {@link #noTooltip}. */
        public Builder euModifier(@Nonnull ToDoubleFunction<Inputs> euModifier) {
            this.euModifier = new Term(euModifier, null);
            return this;
        }

        /** {@code discount} less EU/t per tier of {@code kind}; follow with {@link #maxEuDiscount} for a cap. */
        public Builder euDiscountPerTier(double discount, @Nonnull ModifierKind kind) {
            this.euModifier = new Term(
                in -> 1 - discount * shownTier(in, kind),
                tt -> tt.addDynamicEuEffInfo((float) discount, kind));
            return this;
        }

        /**
         * {@code euModifier} at the first tier of {@code kind}, multiplied by {@code factor} for each tier beyond it:
         * 0.8 and 0.95 read as 80% EU usage, 5% less per tier beyond the first.
         */
        public Builder euModifierPerTierBeyondFirst(double euModifier, double factor, @Nonnull ModifierKind kind) {
            this.euModifier = new Term(
                in -> euModifier * GTUtility.powInt(factor, shownTier(in, kind) - 1),
                tt -> tt.addStaticEuEffInfo((float) euModifier)
                    .addEuMultiplierBeyondFirstInfo((float) factor, kind));
            return this;
        }

        /** Caps the discount of {@link #euDiscountPerTier}, which the tooltip states. */
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

        /** See {@link Quantity#EU_MODIFIER_NOT_LIMITING_PARALLEL}; its tooltip line is {@link #customTooltip}. */
        public Builder euModifierNotLimitingParallel(@Nonnull ToDoubleFunction<Inputs> euModifier) {
            this.euModifierNotLimitingParallel = new Term(euModifier, null);
            return this;
        }

        /** Runs recipes at their own voltage, without overclocks, as {@link OverclockCalculator#ofNoOverclock}. */
        public Builder noOverclock() {
            this.overclock = new OverclockRule.None();
            return this;
        }

        /** 4x speed for 4x EU/t per overclock, which the tooltip states. */
        public Builder perfectOverclock() {
            return overclock(4, 4);
        }

        /** As {@link ProcessingLogic#setOverclock}. */
        public Builder overclock(double durationDivisor, double euMultiplier) {
            this.overclock = new OverclockRule.Ratio(durationDivisor, euMultiplier);
            return this;
        }

        /** As {@link ProcessingLogic#setMaxTierSkips}; the tooltip states more than 1. */
        public Builder maxTierSkips(int tierSkips) {
            this.maxTierSkips = OptionalInt.of(tierSkips);
            return this;
        }

        /** As {@link ProcessingLogic#setUnlimitedTierSkips}, which the tooltip states. */
        public Builder unlimitedTierSkips() {
            return maxTierSkips(Integer.MAX_VALUE);
        }

        /** The machine's heat, such as {@link #COIL_HEAT}, and what it does with it. */
        public Builder heat(@Nonnull ToIntFunction<Inputs> machineHeat, @Nonnull HeatRule... rules) {
            this.heatFunction = machineHeat;
            Collections.addAll(this.heatRules, rules);
            return this;
        }

        /** Overclocks against this heat rather than the recipe's. */
        public Builder recipeHeat(int heat) {
            this.recipeHeat = OptionalInt.of(heat);
            return this;
        }

        /** Runs every recipe at this cost instead of its own. */
        public Builder recipeOverride(@Nonnull RecipeOverride recipeOverride) {
            this.recipeOverride = recipeOverride;
            return this;
        }

        /** Writes these lines instead of the ones the spec would write for {@code quantity}. */
        public Builder customTooltip(@Nonnull Quantity quantity, @Nonnull Consumer<MultiblockTooltipBuilder> lines) {
            this.customTooltips.put(quantity, lines);
            return this;
        }

        /** Writes no line for these quantities: the machine's tooltip covers them elsewhere, or not at all. */
        public Builder noTooltip(@Nonnull Quantity... quantities) {
            Collections.addAll(this.noTooltip, quantities);
            return this;
        }

        /** Marks quantities the machine also changes in code of its own, so the spec alone does not give them. */
        public Builder alsoCustom(@Nonnull Quantity... quantities) {
            Collections.addAll(this.alsoCustom, quantities);
            return this;
        }

        /**
         * The machine's modes, as {@link MTEMultiBlockBase#getMachineModes()} lists them, for naming {@link #inMode}.
         */
        public Builder modes(@Nonnull List<MachineMode> modes) {
            this.modes = Objects.requireNonNull(modes, "declare the modes before the spec");
            return this;
        }

        /**
         * Terms that apply in machine mode {@code mode} only, in place of the spec's own. The tooltip heads their lines
         * with the mode's name from {@link #modes}, and leaves them out for a mode the machine does not have.
         */
        public Builder inMode(int mode, @Nonnull Consumer<Builder> terms) {
            return variant(
                in -> in.mode() == mode,
                modes -> modes != null && mode < modes.size() ? modes.get(mode)
                    .nameKey() : null,
                terms);
        }

        /**
         * Terms that apply while modifier {@code kind} is {@code value} only, in place of the spec's own. The tooltip
         * heads their lines with {@link ModifierKind#label} of the value.
         */
        public Builder whenTier(@Nonnull ModifierKind kind, int value, @Nonnull Consumer<Builder> terms) {
            return variant(in -> in.value(kind) == value, modes -> kind.label(value), terms);
        }

        private Builder variant(Predicate<Inputs> appliesTo, Function<List<MachineMode>, String> name,
            Consumer<Builder> terms) {
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
            this.variants.add(new Variant(appliesTo, name, spec));
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
