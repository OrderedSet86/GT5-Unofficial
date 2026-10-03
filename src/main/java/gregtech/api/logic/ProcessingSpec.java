package gregtech.api.logic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntBiFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongBiFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.OverclockCalculator;
import gregtech.api.util.tooltip.TooltipHelper;

/**
 * What a multiblock does to its recipes, as a function of its energy hatch tier, mode and {@link Modifier}s.
 * {@link MTEMultiBlockBase} applies it to the machine's {@link ProcessingLogic} and tooltip. Planners evaluate it
 * without a world.
 * <p>
 * Keep one per machine class in a static field. Numbers a spec leaves unset stay in the machine's code. Numbers it sets
 * must reach the tooltip: terms write a line, and a plain function requires {@link Builder#customTooltip} or
 * {@link Builder#noTooltip}.
 */
public final class ProcessingSpec {

    /** One energy hatch, as the machine's power getters read it. */
    public record EnergyHatch(long voltage, long amperage, boolean exotic) {

        /** Supplies 2 A. */
        @Nonnull
        public static EnergyHatch regular(int tier) {
            return new EnergyHatch(GTValues.V[tier], 2, false);
        }

        /** A multi-amp or laser hatch, whose amps are all used. */
        @Nonnull
        public static EnergyHatch exotic(int tier, long amperage) {
            return new EnergyHatch(GTValues.V[tier], amperage, true);
        }
    }

    /** @param energyHatches What the machine draws from, including hatches it cannot reach (voltage and amperage 0) */
    public record Inputs(@Nonnull List<EnergyHatch> energyHatches, int mode,
        @Nonnull Map<ModifierKind<?>, Number> values) {

        /**
         * {@link ModifierKind#VOLTAGE} reads {@link #voltageTier}.
         *
         * @throws IllegalArgumentException if no value was given for the kind
         */
        @Nonnull
        @SuppressWarnings("unchecked") // Builder.value() puts a T under each ModifierKind<T>
        public <T extends Number & Comparable<T>> T value(@Nonnull ModifierKind<T> kind) {
            if (ModifierKind.VOLTAGE.equals(kind)) return (T) Integer.valueOf(voltageTier());
            Number value = values.get(kind);
            if (value == null) throw new IllegalArgumentException("no " + kind + " value given");
            return (T) value;
        }

        /** The tier of the summed hatch voltages, as parallels per voltage tier read it. */
        public int voltageTier() {
            return GTUtility.getTier(totalVoltage());
        }

        /** 0 without hatches. */
        public long averageVoltage() {
            return energyHatches.isEmpty() ? 0 : totalVoltage() / energyHatches.size();
        }

        public long totalVoltage() {
            long voltage = 0;
            for (EnergyHatch hatch : energyHatches) voltage += hatch.voltage;
            return voltage;
        }

        public long amperage() {
            long amperage = 0;
            for (EnergyHatch hatch : energyHatches) amperage += hatch.amperage;
            return amperage;
        }

        /** Saturates at {@link Long#MAX_VALUE}. */
        public long totalEu() {
            long eu = 0;
            for (EnergyHatch hatch : energyHatches)
                eu = GTUtility.addSafe(eu, GTUtility.mulSafe(hatch.voltage, hatch.amperage));
            return eu;
        }

        /** A standard multiblock draws 1 A of such a hatch's 2 A. */
        public boolean isSingleRegularHatch() {
            return energyHatches.size() == 1 && !energyHatches.get(0).exotic;
        }

        @Nonnull
        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {

            private final List<EnergyHatch> energyHatches = new ArrayList<>();
            private int mode;
            private final Map<ModifierKind<?>, Number> values = new HashMap<>();

            private Builder() {}

            public Builder energyHatch(@Nonnull EnergyHatch hatch) {
                this.energyHatches.add(hatch);
                return this;
            }

            /** {@code count} regular hatches. */
            public Builder energyHatches(int tier, int count) {
                for (int i = 0; i < count; i++) energyHatch(EnergyHatch.regular(tier));
                return this;
            }

            public Builder energyHatches(@Nonnull List<EnergyHatch> hatches) {
                this.energyHatches.addAll(hatches);
                return this;
            }

            public Builder mode(int mode) {
                this.mode = mode;
                return this;
            }

            /** @throws IllegalArgumentException for {@link ModifierKind#VOLTAGE}, which the energy hatches give */
            public <T extends Number & Comparable<T>> Builder value(@Nonnull ModifierKind<T> kind, @Nonnull T value) {
                if (ModifierKind.VOLTAGE.equals(kind)) {
                    throw new IllegalArgumentException("the energy hatches give the voltage tier");
                }
                this.values.put(kind, value);
                return this;
            }

            public Builder modifiers(@Nonnull List<? extends Modifier<?>> modifiers) {
                for (Modifier<?> modifier : modifiers) putCurrent(modifier);
                return this;
            }

            /** Each modifier at its max, which makes the best machine only for {@link ModifierKind#ordered} kinds. */
            public Builder modifiersAtMax(@Nonnull List<? extends Modifier<?>> modifiers) {
                for (Modifier<?> modifier : modifiers) putMax(modifier);
                return this;
            }

            private <T extends Number & Comparable<T>> void putCurrent(Modifier<T> modifier) {
                value(modifier.kind, modifier.get());
            }

            private <T extends Number & Comparable<T>> void putMax(Modifier<T> modifier) {
                value(modifier.kind, modifier.max);
            }

            @Nonnull
            public Inputs build() {
                return new Inputs(
                    Collections.unmodifiableList(new ArrayList<>(energyHatches)),
                    mode,
                    Collections.unmodifiableMap(new HashMap<>(values)));
            }
        }
    }

    /**
     * The voltage and amperage a machine's recipes see.
     *
     * @param unlimited Energy limits neither parallels nor overclocks. The recipe's voltage check still reads
     *                  {@code voltage}.
     */
    public record Power(long voltage, long amperage, boolean amperageOverclock, boolean unlimited) {}

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

            public static final Ratio STANDARD = new Ratio(2, 4);
            public static final Ratio PERFECT = new Ratio(4, 4);

            public boolean isPerfect() {
                return equals(PERFECT);
            }
        }
    }

    public enum HeatRule {
        /** Perfect overclocks for every 1800K the machine's heat exceeds the recipe's. */
        OVERCLOCK,
        /** 5% less EU/t for every 900K the machine's heat exceeds the recipe's. */
        DISCOUNT,
        /** Recipes hotter than the machine cannot run. */
        REQUIRED
    }

    public record Heat(@Nonnull ToIntFunction<Inputs> machineHeat, @Nonnull Set<HeatRule> rules,
        @Nonnull OptionalInt fixedRecipeHeat) {

        public int getMachineHeat(@Nonnull Inputs inputs) {
            return machineHeat.applyAsInt(inputs);
        }

        public int getRecipeHeat(@Nonnull GTRecipe recipe) {
            return fixedRecipeHeat.orElse(recipe.mSpecialValue);
        }

        public boolean isOverclocking() {
            return rules.contains(HeatRule.OVERCLOCK);
        }

        public boolean isDiscounting() {
            return rules.contains(HeatRule.DISCOUNT);
        }

        public boolean isRequired() {
            return rules.contains(HeatRule.REQUIRED);
        }
    }

    /** In tooltip order. */
    public enum Quantity {
        PARALLEL,
        DURATION,
        EU_MODIFIER,
        /** Unlike {@link #EU_MODIFIER}, does not lower the parallels energy allows. */
        EU_MODIFIER_NOT_LIMITING_PARALLEL,
        /** Multiplies the recipe's EU/t, before overclocks and before its voltage is checked. */
        RECIPE_EU_MULTIPLIER,
        OVERCLOCK,
        TIER_SKIPS,
        HEAT,
        RECIPE_OVERRIDE,
        /** Voltage, amperage, and EU taken or given once per start or run. */
        POWER,
        /** Success chance and yield. */
        OUTPUT
    }

    /**
     * @param result            Unsuccessful means every number is 0
     * @param parallel          After the energy limit
     * @param euPerTick         For all parallels together
     * @param startupEu         Taken once when the machine starts from idle
     * @param euPerRun          Taken when each run starts, besides {@code euPerTick}
     * @param euGeneratedPerRun Given when each run ends
     * @param successChance     Of each parallel, 0 to 1
     * @param outputYield       Multiplies the outputs of each parallel that succeeds
     */
    public record Run(@Nonnull CheckRecipeResult result, int parallel, int overclocks, int ticks, long euPerTick,
        long startupEu, @Nonnull BigInteger euPerRun, @Nonnull BigInteger euGeneratedPerRun, double successChance,
        double outputYield) {

        @Nonnull
        public static Run failed(@Nonnull CheckRecipeResult result) {
            return builder(result).successChance(0)
                .outputYield(0)
                .build();
        }

        @Nonnull
        public static Builder builder(@Nonnull CheckRecipeResult result) {
            return new Builder(result);
        }

        public static final class Builder {

            private final CheckRecipeResult result;
            private int parallel;
            private int overclocks;
            private int ticks;
            private long euPerTick;
            private long startupEu;
            private BigInteger euPerRun = BigInteger.ZERO;
            private BigInteger euGeneratedPerRun = BigInteger.ZERO;
            private double successChance = 1;
            private double outputYield = 1;

            private Builder(CheckRecipeResult result) {
                this.result = result;
            }

            public Builder parallel(int parallel) {
                this.parallel = parallel;
                return this;
            }

            public Builder overclocks(int overclocks) {
                this.overclocks = overclocks;
                return this;
            }

            public Builder ticks(int ticks) {
                this.ticks = ticks;
                return this;
            }

            public Builder euPerTick(long euPerTick) {
                this.euPerTick = euPerTick;
                return this;
            }

            public Builder startupEu(long startupEu) {
                this.startupEu = startupEu;
                return this;
            }

            public Builder euPerRun(@Nonnull BigInteger euPerRun) {
                this.euPerRun = euPerRun;
                return this;
            }

            public Builder euGeneratedPerRun(@Nonnull BigInteger euGeneratedPerRun) {
                this.euGeneratedPerRun = euGeneratedPerRun;
                return this;
            }

            public Builder successChance(double successChance) {
                this.successChance = successChance;
                return this;
            }

            public Builder outputYield(double outputYield) {
                this.outputYield = outputYield;
                return this;
            }

            @Nonnull
            public Run build() {
                return new Run(
                    result,
                    parallel,
                    overclocks,
                    ticks,
                    euPerTick,
                    startupEu,
                    euPerRun,
                    euGeneratedPerRun,
                    successChance,
                    outputYield);
            }
        }
    }

    public static final ToIntFunction<Inputs> COIL_HEAT = in -> (int) HeatingCoilLevel
        .getFromTier(
            in.value(ModifierKind.COIL)
                .byteValue())
        .getHeat();

    private static final ToLongFunction<Inputs> STANDARD_VOLTAGE = Inputs::averageVoltage;
    private static final ToLongFunction<Inputs> STANDARD_AMPERAGE = in -> in.isSingleRegularHatch() ? 1 : in.amperage();

    /** For a machine that runs a plain {@link ProcessingLogic}. */
    public static final ProcessingSpec STANDARD = builder().build();

    /** @param perRecipe Reads the recipe, so it applies only once one is known */
    private record ParallelTerm(ToIntBiFunction<Inputs, GTRecipe> parallel, boolean perRecipe,
        @Nullable Consumer<MultiblockTooltipBuilder> tooltip) {}

    private record Requirement(BiPredicate<Inputs, GTRecipe> met,
        BiFunction<Inputs, GTRecipe, CheckRecipeResult> failure) {}

    private record Term(ToDoubleFunction<Inputs> factor, @Nullable Consumer<MultiblockTooltipBuilder> tooltip) {}

    /** @param name Heads the tooltip lines, given the spec's modes */
    private record Variant(Predicate<Inputs> appliesTo, Function<List<MachineMode>, String> name,
        @Nullable Integer mode, ProcessingSpec terms) {}

    private static final Set<Quantity> SETTABLE_IN_VARIANT = Collections.unmodifiableSet(
        EnumSet.of(
            Quantity.PARALLEL,
            Quantity.DURATION,
            Quantity.EU_MODIFIER,
            Quantity.EU_MODIFIER_NOT_LIMITING_PARALLEL,
            Quantity.RECIPE_EU_MULTIPLIER,
            Quantity.OVERCLOCK));

    private final List<ParallelTerm> parallel;
    private final boolean parallelReadsRecipe;
    @Nullable
    private final Term duration;
    @Nullable
    private final Term euModifier;
    @Nullable
    private final Term euModifierNotLimitingParallel;
    @Nullable
    private final Term recipeEuMultiplier;
    @Nullable
    private final OverclockRule overclock;
    private final OptionalInt maxTierSkips;
    @Nullable
    private final Heat heat;
    @Nullable
    private final Consumer<MultiblockTooltipBuilder> heatTooltip;
    @Nullable
    private final ToIntBiFunction<Inputs, GTRecipe> recipeDuration;
    @Nullable
    private final ToIntBiFunction<Inputs, GTRecipe> maxOverclocks;
    @Nullable
    private final Consumer<MultiblockTooltipBuilder> maxOverclocksTooltip;
    private final List<Requirement> requirements;
    private final List<Requirement> startRequirements;
    @Nullable
    private final RecipeOverride recipeOverride;
    @Nullable
    private final ToLongFunction<Inputs> voltage;
    @Nullable
    private final ToLongFunction<Inputs> amperage;
    private final boolean noAmperageOverclock;
    @Nullable
    private final Predicate<Inputs> unlimitedEnergy;
    @Nullable
    private final ToLongFunction<Inputs> maxEuPerTick;
    @Nullable
    private final ToLongBiFunction<Inputs, GTRecipe> startupEu;
    @Nullable
    private final BiFunction<Inputs, GTRecipe, BigInteger> euPerRun;
    @Nullable
    private final BiFunction<Inputs, GTRecipe, BigInteger> euGeneratedPerRun;
    @Nullable
    private final ToDoubleBiFunction<Inputs, GTRecipe> successChance;
    @Nullable
    private final ToDoubleBiFunction<Inputs, GTRecipe> outputYield;
    private final Map<Quantity, Consumer<MultiblockTooltipBuilder>> customTooltips;
    private final Set<Quantity> noTooltip;
    private final Set<Quantity> alsoCustom;
    private final List<MachineMode> modes;
    private final List<Variant> variants;
    private final Set<Integer> unsupportedModes;

    private ProcessingSpec(Builder builder) {
        this.parallel = new ArrayList<>(builder.parallel);
        this.parallelReadsRecipe = parallel.stream()
            .anyMatch(ParallelTerm::perRecipe);
        this.duration = builder.duration;
        this.euModifier = builder.euModifier;
        this.euModifierNotLimitingParallel = builder.euModifierNotLimitingParallel;
        this.recipeEuMultiplier = builder.recipeEuMultiplier;
        this.overclock = builder.overclock;
        this.maxTierSkips = builder.maxTierSkips;
        Heat heat = builder.heatFunction == null ? null
            : new Heat(
                builder.heatFunction,
                Collections.unmodifiableSet(EnumSet.copyOf(builder.heatRules)),
                builder.recipeHeat);
        this.heat = heat;
        this.heatTooltip = builder.heatTooltip;
        this.recipeOverride = builder.recipeOverride;
        this.customTooltips = new EnumMap<>(builder.customTooltips);
        this.noTooltip = builder.noTooltip.clone();
        this.alsoCustom = builder.alsoCustom.clone();
        this.modes = builder.modes == null ? null : new ArrayList<>(builder.modes);
        this.variants = new ArrayList<>(builder.variants);
        this.unsupportedModes = Collections.unmodifiableSet(new HashSet<>(builder.unsupportedModes));
        this.recipeDuration = builder.recipeDuration;
        this.maxOverclocks = builder.maxOverclocks;
        this.maxOverclocksTooltip = builder.maxOverclocksTooltip;
        this.requirements = new ArrayList<>();
        if (heat != null && heat.isRequired()) {
            requirements.add(
                new Requirement(
                    (in, recipe) -> heat.getRecipeHeat(recipe) <= heat.getMachineHeat(in),
                    (in, recipe) -> CheckRecipeResultRegistry.insufficientHeat(heat.getRecipeHeat(recipe))));
        }
        requirements.addAll(builder.requirements);
        this.startRequirements = new ArrayList<>(builder.startRequirements);
        this.voltage = builder.voltage;
        this.amperage = builder.amperage;
        this.noAmperageOverclock = builder.noAmperageOverclock;
        this.unlimitedEnergy = builder.unlimitedEnergy;
        this.maxEuPerTick = builder.maxEuPerTick;
        this.startupEu = builder.startupEu;
        this.euPerRun = builder.euPerRun;
        this.euGeneratedPerRun = builder.euGeneratedPerRun;
        this.successChance = builder.successChance;
        this.outputYield = builder.outputYield;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** At least 1. Leaves out the terms that read the recipe, as a display without one does. */
    public int getMaxParallel(@Nonnull Inputs inputs) {
        return maxParallel(inputs, null);
    }

    /** At least 1. */
    public int getMaxParallel(@Nonnull Inputs inputs, @Nonnull GTRecipe recipe) {
        return maxParallel(inputs, recipe);
    }

    private int maxParallel(Inputs inputs, @Nullable GTRecipe recipe) {
        int sum = 0;
        for (ParallelTerm term : resolve(inputs, spec -> spec.parallel, terms -> !terms.isEmpty())) {
            if (term.perRecipe && recipe == null) continue;
            sum += term.parallel.applyAsInt(inputs, recipe);
        }
        return Math.max(1, sum);
    }

    /** The recipe's duration in ticks before overclocks, if the spec replaces the recipe's. */
    @Nonnull
    public OptionalInt getRecipeDuration(@Nonnull Inputs inputs, @Nonnull GTRecipe recipe) {
        return recipeDuration == null ? OptionalInt.empty() : OptionalInt.of(recipeDuration.applyAsInt(inputs, recipe));
    }

    @Nonnull
    public OptionalInt getMaxOverclocks(@Nonnull Inputs inputs, @Nonnull GTRecipe recipe) {
        return maxOverclocks == null ? OptionalInt.empty() : OptionalInt.of(maxOverclocks.applyAsInt(inputs, recipe));
    }

    /** Whether the spec's number for this quantity depends on the recipe. */
    public boolean readsRecipe(@Nonnull Quantity quantity) {
        return switch (quantity) {
            case PARALLEL -> parallelReadsRecipe;
            case DURATION -> recipeDuration != null;
            case EU_MODIFIER, EU_MODIFIER_NOT_LIMITING_PARALLEL, RECIPE_EU_MULTIPLIER, TIER_SKIPS -> false;
            case OVERCLOCK -> maxOverclocks != null;
            case HEAT -> heat != null;
            case RECIPE_OVERRIDE -> recipeOverride != null;
            case POWER -> startupEu != null || euPerRun != null
                || euGeneratedPerRun != null
                || !startRequirements.isEmpty();
            case OUTPUT -> successChance != null || outputYield != null;
        };
    }

    /**
     * Whether the machine can run the recipe at these inputs: the first requirement it fails, heat first, else
     * success.
     */
    @Nonnull
    public CheckRecipeResult check(@Nonnull GTRecipe recipe, @Nonnull Inputs inputs) {
        return firstFailing(requirements, recipe, inputs);
    }

    /**
     * The first requirement to start from idle that fails, else success. Machines check these where they start, and
     * {@link #calculate} checks them since a planner starts from idle.
     */
    @Nonnull
    public CheckRecipeResult checkToStart(@Nonnull GTRecipe recipe, @Nonnull Inputs inputs) {
        return firstFailing(startRequirements, recipe, inputs);
    }

    private static CheckRecipeResult firstFailing(List<Requirement> requirements, GTRecipe recipe, Inputs inputs) {
        for (Requirement requirement : requirements) {
            if (!requirement.met.test(inputs, recipe)) return requirement.failure.apply(inputs, recipe);
        }
        return CheckRecipeResultRegistry.SUCCESSFUL;
    }

    @Nonnull
    public Power getPower(@Nonnull Inputs inputs) {
        return new Power(
            (voltage == null ? STANDARD_VOLTAGE : voltage).applyAsLong(inputs),
            (amperage == null ? STANDARD_AMPERAGE : amperage).applyAsLong(inputs),
            !noAmperageOverclock,
            unlimitedEnergy != null && unlimitedEnergy.test(inputs));
    }

    /** {@link Long#MAX_VALUE} unless the spec caps it. */
    public long getMaxEuPerTick(@Nonnull Inputs inputs) {
        return maxEuPerTick == null ? Long.MAX_VALUE : maxEuPerTick.applyAsLong(inputs);
    }

    /** 0 unless the spec sets it. */
    public long getStartupEu(@Nonnull Inputs inputs, @Nonnull GTRecipe recipe) {
        return startupEu == null ? 0 : startupEu.applyAsLong(inputs, recipe);
    }

    /** 0 unless the spec sets it. */
    @Nonnull
    public BigInteger getEuPerRun(@Nonnull Inputs inputs, @Nonnull GTRecipe recipe) {
        return euPerRun == null ? BigInteger.ZERO : euPerRun.apply(inputs, recipe);
    }

    /** 0 unless the spec sets it. */
    @Nonnull
    public BigInteger getEuGeneratedPerRun(@Nonnull Inputs inputs, @Nonnull GTRecipe recipe) {
        return euGeneratedPerRun == null ? BigInteger.ZERO : euGeneratedPerRun.apply(inputs, recipe);
    }

    /** 1 unless the spec sets it. */
    public double getSuccessChance(@Nonnull Inputs inputs, @Nonnull GTRecipe recipe) {
        return successChance == null ? 1 : successChance.applyAsDouble(inputs, recipe);
    }

    /** 1 unless the spec sets it. */
    public double getOutputYield(@Nonnull Inputs inputs, @Nonnull GTRecipe recipe) {
        return outputYield == null ? 1 : outputYield.applyAsDouble(inputs, recipe);
    }

    /** 0.5 halves recipe time. */
    public double getDurationMultiplier(@Nonnull Inputs inputs) {
        return factor(inputs, spec -> spec.duration);
    }

    public double getEuModifier(@Nonnull Inputs inputs) {
        return factor(inputs, spec -> spec.euModifier);
    }

    public double getEuModifierNotLimitingParallel(@Nonnull Inputs inputs) {
        return factor(inputs, spec -> spec.euModifierNotLimitingParallel);
    }

    public double getRecipeEuMultiplier(@Nonnull Inputs inputs) {
        return factor(inputs, spec -> spec.recipeEuMultiplier);
    }

    /** 1 where the spec sets no term. */
    private double factor(Inputs inputs, Function<ProcessingSpec, Term> term) {
        Term resolved = resolve(inputs, term, Objects::nonNull);
        return resolved == null ? 1 : resolved.factor.applyAsDouble(inputs);
    }

    /** The first matching variant's setting, else the spec's. */
    private <T> T resolve(Inputs inputs, Function<ProcessingSpec, T> setting, Predicate<T> isSet) {
        for (Variant variant : variants) {
            T variantSetting = setting.apply(variant.terms);
            if (isSet.test(variantSetting) && variant.appliesTo.test(inputs)) return variantSetting;
        }
        return setting.apply(this);
    }

    /**
     * The first matching variant's rule, else the spec's. {@link OverclockRule.Ratio#STANDARD} where only variants set
     * a rule and none matches.
     */
    @Nonnull
    public Optional<OverclockRule> getOverclock(@Nonnull Inputs inputs) {
        OverclockRule rule = resolve(inputs, spec -> spec.overclock, Objects::nonNull);
        if (rule != null) return Optional.of(rule);
        for (Variant variant : variants) {
            if (variant.terms.overclock != null) return Optional.of(OverclockRule.Ratio.STANDARD);
        }
        return Optional.empty();
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
            case DURATION -> duration != null || recipeDuration != null;
            case EU_MODIFIER -> euModifier != null;
            case EU_MODIFIER_NOT_LIMITING_PARALLEL -> euModifierNotLimitingParallel != null;
            case RECIPE_EU_MULTIPLIER -> recipeEuMultiplier != null;
            case OVERCLOCK -> overclock != null || maxOverclocks != null;
            case TIER_SKIPS -> maxTierSkips.isPresent();
            case HEAT -> heat != null;
            case RECIPE_OVERRIDE -> recipeOverride != null;
            case POWER -> voltage != null || amperage != null
                || noAmperageOverclock
                || unlimitedEnergy != null
                || maxEuPerTick != null
                || startupEu != null
                || !startRequirements.isEmpty()
                || euPerRun != null
                || euGeneratedPerRun != null;
            case OUTPUT -> successChance != null || outputYield != null;
        };
    }

    /** False if the machine's code also changes numbers ({@link Builder#alsoCustom}). For those, ask the machine. */
    public boolean isComplete() {
        return alsoCustom.isEmpty();
    }

    @Nonnull
    public Set<Quantity> getAlsoCustom() {
        return Collections.unmodifiableSet(alsoCustom);
    }

    /** False in a mode whose numbers the machine's code sets. {@link #calculate} throws there. */
    public boolean supportsMode(int mode) {
        return !unsupportedModes.contains(mode);
    }

    /**
     * As {@link ProcessingLogic} would run the recipe.
     *
     * @throws IllegalArgumentException in a mode the spec does not support
     */
    @Nonnull
    public Run calculate(@Nonnull GTRecipe recipe, @Nonnull Inputs inputs) {
        if (!supportsMode(inputs.mode())) {
            throw new IllegalArgumentException("the spec does not describe mode " + inputs.mode());
        }
        return new ProcessingLogic().applySpec(this, inputs)
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
        Set<Quantity> ownDescribed = writeLines(tt, EnumSet.noneOf(Quantity.class));
        List<Set<Quantity>> variantDescribed = new ArrayList<>();
        for (Variant variant : variants) {
            MultiblockTooltipBuilder lines = new MultiblockTooltipBuilder();
            variantDescribed.add(variant.terms.writeLines(lines, overridden));
            if (tt.markSpecLinesWritten(variant)) {
                String name = StatCollector.translateToLocal(variant.name.apply(modes));
                tt.addLinesFrom(EnumChatFormatting.WHITE + name + EnumChatFormatting.GRAY + ": ", lines);
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
            if (tt.markSpecLinesWritten(variant)) variant.terms.writeLines(tt, overridden());
        }
        if (!any) throw new IllegalArgumentException("no inMode terms for mode " + mode);
    }

    private EnumSet<Quantity> overridden() {
        EnumSet<Quantity> overridden = EnumSet.copyOf(noTooltip);
        overridden.addAll(customTooltips.keySet());
        return overridden;
    }

    private EnumSet<Quantity> writeLines(MultiblockTooltipBuilder tt, Set<Quantity> skip) {
        EnumSet<Quantity> described = EnumSet.noneOf(Quantity.class);
        Set<Consumer<MultiblockTooltipBuilder>> customWritten = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Quantity quantity : Quantity.values()) {
            if (skip.contains(quantity)) continue;
            if (noTooltip.contains(quantity)) {
                described.add(quantity);
                continue;
            }
            Consumer<MultiblockTooltipBuilder> custom = customTooltips.get(quantity);
            if (custom != null) {
                // one customTooltip can cover several quantities
                if (customWritten.add(custom)) custom.accept(tt);
                described.add(quantity);
                continue;
            }
            if (writeOwnLines(quantity, tt)) described.add(quantity);
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

    private boolean writeOwnLines(Quantity quantity, MultiblockTooltipBuilder tt) {
        return switch (quantity) {
            case PARALLEL -> {
                boolean all = !parallel.isEmpty();
                for (ParallelTerm term : parallel) {
                    if (term.tooltip == null) all = false;
                    else term.tooltip.accept(tt);
                }
                yield all;
            }
            case DURATION -> writeLine(duration, tt);
            case EU_MODIFIER -> writeLine(euModifier, tt);
            case EU_MODIFIER_NOT_LIMITING_PARALLEL -> writeLine(euModifierNotLimitingParallel, tt);
            case OVERCLOCK -> {
                if (overclock instanceof OverclockRule.Ratio ratio && !ratio.equals(OverclockRule.Ratio.STANDARD)) {
                    tt.addOverclockRatioInfo(ratio.durationDivisor(), ratio.euMultiplier());
                }
                if (maxOverclocks == null) yield true;
                if (maxOverclocksTooltip == null) yield false;
                maxOverclocksTooltip.accept(tt);
                yield true;
            }
            case TIER_SKIPS -> {
                int skips = maxTierSkips.orElse(1);
                if (skips == Integer.MAX_VALUE) tt.addUnlimitedTierSkips();
                else if (skips > 1) tt.addMaxTierSkips(skips);
                yield true;
            }
            case RECIPE_EU_MULTIPLIER -> writeLine(recipeEuMultiplier, tt);
            case HEAT -> {
                if (heat == null || heatTooltip == null
                    || heat.fixedRecipeHeat()
                        .isPresent()) {
                    yield false;
                }
                heatTooltip.accept(tt);
                if (heat.isDiscounting()) tt.addHeatDiscountInfo();
                if (heat.isOverclocking()) tt.addHeatOverclockInfo();
                yield true;
            }
            case RECIPE_OVERRIDE -> {
                if (recipeOverride == null) yield false;
                tt.addRecipeOverrideInfo(recipeOverride.eut(), recipeOverride.duration());
                yield true;
            }
            case POWER, OUTPUT -> false;
        };
    }

    private static boolean writeLine(@Nullable Term term, MultiblockTooltipBuilder tt) {
        if (term == null || term.tooltip == null) return false;
        term.tooltip.accept(tt);
        return true;
    }

    public static final class Builder {

        private final List<ParallelTerm> parallel = new ArrayList<>();
        private Term duration;
        private Term euModifier;
        private Term euModifierNotLimitingParallel;
        private Term recipeEuMultiplier;
        private OverclockRule overclock;
        private OptionalInt maxTierSkips = OptionalInt.empty();
        private ToIntFunction<Inputs> heatFunction;
        private Consumer<MultiblockTooltipBuilder> heatTooltip;
        private final EnumSet<HeatRule> heatRules = EnumSet.noneOf(HeatRule.class);
        private OptionalInt recipeHeat = OptionalInt.empty();
        private RecipeOverride recipeOverride;
        private final EnumMap<Quantity, Consumer<MultiblockTooltipBuilder>> customTooltips = new EnumMap<>(
            Quantity.class);
        private final EnumSet<Quantity> noTooltip = EnumSet.noneOf(Quantity.class);
        private final EnumSet<Quantity> alsoCustom = EnumSet.noneOf(Quantity.class);
        private List<MachineMode> modes;
        private final List<Variant> variants = new ArrayList<>();
        private final Set<Integer> unsupportedModes = new HashSet<>();
        private ToIntBiFunction<Inputs, GTRecipe> recipeDuration;
        private ToIntBiFunction<Inputs, GTRecipe> maxOverclocks;
        private Consumer<MultiblockTooltipBuilder> maxOverclocksTooltip;
        private final List<Requirement> requirements = new ArrayList<>();
        private final List<Requirement> startRequirements = new ArrayList<>();
        private ToLongFunction<Inputs> voltage;
        private ToLongFunction<Inputs> amperage;
        private boolean noAmperageOverclock;
        private Predicate<Inputs> unlimitedEnergy;
        private ToLongFunction<Inputs> maxEuPerTick;
        private ToLongBiFunction<Inputs, GTRecipe> startupEu;
        private BiFunction<Inputs, GTRecipe, BigInteger> euPerRun;
        private BiFunction<Inputs, GTRecipe, BigInteger> euGeneratedPerRun;
        private ToDoubleBiFunction<Inputs, GTRecipe> successChance;
        private ToDoubleBiFunction<Inputs, GTRecipe> outputYield;

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
        @SafeVarargs
        public final Builder parallelPerTier(int parallel, @Nonnull ModifierKind<Integer>... kinds) {
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
                for (ModifierKind<Integer> kind : kinds) product *= kind.countedTier(in.value(kind));
                return product;
            }, tooltip);
        }

        /** From {@code min} to {@code max} per voltage tier as {@code kind} rises from 0 to {@code kindMax}. */
        public Builder parallelPerVoltageTierRising(int min, int max, @Nonnull ModifierKind<Integer> kind,
            int kindMax) {
            return parallelTerm(
                in -> (int) ((min + (max - min) * in.value(kind) / (float) kindMax) * in.voltageTier()),
                tt -> tt.addRisingParallelPerVoltageTierInfo(min, max, kind));
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

        /** Adds a parallel that depends on the recipe. */
        public Builder parallelPerRecipe(@Nonnull ToIntBiFunction<Inputs, GTRecipe> parallel) {
            this.parallel.add(new ParallelTerm(parallel, true, null));
            return this;
        }

        /** The recipe's duration in ticks before overclocks, replacing the recipe's. */
        public Builder durationPerRecipe(@Nonnull ToIntBiFunction<Inputs, GTRecipe> ticks) {
            this.recipeDuration = ticks;
            return this;
        }

        /** Caps the overclocks per recipe. */
        public Builder maxOverclocksPerRecipe(@Nonnull ToIntBiFunction<Inputs, GTRecipe> maxOverclocks) {
            this.maxOverclocks = maxOverclocks;
            return this;
        }

        public Builder maxOverclocksPerRecipe(@Nonnull ToIntBiFunction<Inputs, GTRecipe> maxOverclocks,
            @Nonnull Consumer<MultiblockTooltipBuilder> tooltip) {
            this.maxOverclocksTooltip = tooltip;
            return maxOverclocksPerRecipe(maxOverclocks);
        }

        /** The check returns the first unmet requirement's {@code failure}, in declaration order. */
        public Builder requires(@Nonnull BiPredicate<Inputs, GTRecipe> met,
            @Nonnull BiFunction<Inputs, GTRecipe, CheckRecipeResult> failure) {
            this.requirements.add(new Requirement(met, failure));
            return this;
        }

        private Builder parallelTerm(ToIntFunction<Inputs> parallel,
            @Nullable Consumer<MultiblockTooltipBuilder> tooltip) {
            this.parallel.add(new ParallelTerm((in, recipe) -> parallel.applyAsInt(in), false, tooltip));
            return this;
        }

        /** 2.5 is 250% speed: recipes take 1 / 2.5 of the time. */
        public Builder speed(double speed) {
            return speedTerm(in -> speed, tt -> tt.addStaticSpeedInfo((float) speed));
        }

        public Builder speed(@Nonnull ToDoubleFunction<Inputs> speed) {
            return speedTerm(speed, null);
        }

        /** {@code base} plus {@code perTier} per tier of {@code kind}: 1 and 1 is 200% at the first tier. */
        public Builder speedPerTier(double base, double perTier, @Nonnull ModifierKind<Integer> kind) {
            return speedTerm(
                in -> base + perTier * kind.countedTier(in.value(kind)),
                tt -> tt.addSpeedPerTierInfo((float) base, (float) perTier, kind));
        }

        /** From {@code min} to {@code max} speed as {@code kind} rises from 0 to {@code kindMax}. */
        public Builder speedRising(double min, double max, @Nonnull ModifierKind<Integer> kind, int kindMax) {
            return speedTerm(
                in -> min + (max - min) * in.value(kind) / kindMax,
                tt -> tt.addRisingSpeedInfo((float) min, (float) max, kind));
        }

        /** {@code first} at the first tier, plus {@code perTier} per further tier. */
        public Builder speedPerTierBeyondFirst(double first, double perTier, @Nonnull ModifierKind<Integer> kind) {
            return speedTerm(
                in -> first + perTier * (kind.countedTier(in.value(kind)) - 1),
                tt -> tt.addSpeedPerTierBeyondFirstInfo((float) first, (float) perTier, kind));
        }

        private Builder speedTerm(ToDoubleFunction<Inputs> speed,
            @Nullable Consumer<MultiblockTooltipBuilder> tooltip) {
            this.duration = new Term(in -> 1 / speed.applyAsDouble(in), tooltip);
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
        public Builder euDiscountPerTier(double discount, @Nonnull ModifierKind<Integer> kind) {
            this.euModifier = new Term(
                in -> 1 - discount * kind.countedTier(in.value(kind)),
                tt -> tt.addDynamicEuEffInfo((float) discount, kind));
            return this;
        }

        /** {@code euModifier} at the first tier, times {@code factor} per further tier. */
        public Builder euModifierPerTierBeyondFirst(double euModifier, double factor,
            @Nonnull ModifierKind<Integer> kind) {
            this.euModifier = new Term(
                in -> euModifier * GTUtility.powInt(factor, kind.countedTier(in.value(kind)) - 1),
                tt -> tt.addStaticEuEffInfo((float) euModifier)
                    .addEuMultiplierBeyondFirstInfo((float) factor, kind));
            return this;
        }

        public Builder maxEuDiscount(double maxDiscount) {
            Term perTier = this.euModifier;
            if (perTier == null || perTier.tooltip == null) {
                throw new IllegalStateException("maxEuDiscount follows an euModifier with a tooltip");
            }
            this.euModifier = new Term(
                in -> Math.max(perTier.factor.applyAsDouble(in), 1 - maxDiscount),
                perTier.tooltip.andThen(
                    tt -> tt.addInfo("Maximum of " + TooltipHelper.effText((float) maxDiscount) + " EU discount")));
            return this;
        }

        /** Multiplies the recipe's EU/t, up to {@link Integer#MAX_VALUE}, as if the recipe were costlier. */
        public Builder recipeEuMultiplier(double multiplier) {
            this.recipeEuMultiplier = new Term(in -> multiplier, tt -> tt.addRecipeEuMultiplierInfo(multiplier));
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
            this.overclock = OverclockRule.Ratio.PERFECT;
            return this;
        }

        public Builder overclock(double durationDivisor, double euMultiplier) {
            this.overclock = new OverclockRule.Ratio(durationDivisor, euMultiplier);
            return this;
        }

        /** Values of 1 and below get no tooltip line. */
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

        /** Coil heat, plus {@code heatPerTier} K for every voltage tier past {@code baseVoltageTier}. */
        public Builder coilHeatPerVoltageTier(int heatPerTier, int baseVoltageTier, @Nonnull HeatRule... rules) {
            heat(in -> COIL_HEAT.applyAsInt(in) + heatPerTier * (in.voltageTier() - baseVoltageTier), rules);
            this.heatTooltip = tt -> tt.addHeatPerVoltageTierInfo(heatPerTier, baseVoltageTier);
            return this;
        }

        /** Every heat rule compares against this heat, not the recipe's. */
        public Builder recipeHeat(int heat) {
            this.recipeHeat = OptionalInt.of(heat);
            return this;
        }

        public Builder recipeOverride(@Nonnull RecipeOverride recipeOverride) {
            this.recipeOverride = recipeOverride;
            return this;
        }

        /**
         * Replaces the standard power: the average hatch voltage, and all amps except that only 1 A of a lone regular
         * hatch is used.
         */
        public Builder power(@Nonnull ToLongFunction<Inputs> voltage, @Nonnull ToLongFunction<Inputs> amperage) {
            this.voltage = voltage;
            this.amperage = amperage;
            return this;
        }

        /** All hatch EU as one amp. */
        public Builder powerAtOneAmp() {
            return power(Inputs::totalEu, in -> 1);
        }

        /** Uses both amps of a lone regular hatch too. */
        public Builder allAmps() {
            return power(STANDARD_VOLTAGE, Inputs::amperage);
        }

        /** Extra amps add parallels only, not overclocks. */
        public Builder noAmperageOverclock() {
            this.noAmperageOverclock = true;
            return this;
        }

        /** The most EU/t a run draws, whatever its overclocks cost. */
        public Builder maxEuPerTick(@Nonnull ToLongFunction<Inputs> maxEuPerTick) {
            this.maxEuPerTick = maxEuPerTick;
            return this;
        }

        /**
         * Where {@code when} is true, energy limits neither parallels nor overclocks. The recipe's voltage check still
         * reads the voltage.
         */
        public Builder unlimitedEnergy(@Nonnull Predicate<Inputs> when) {
            this.unlimitedEnergy = when;
            return this;
        }

        /** A requirement to start from idle, such as enough power to ignite. Part of {@link Quantity#POWER}. */
        public Builder requiresToStart(@Nonnull BiPredicate<Inputs, GTRecipe> met,
            @Nonnull BiFunction<Inputs, GTRecipe, CheckRecipeResult> failure) {
            this.startRequirements.add(new Requirement(met, failure));
            return this;
        }

        /** EU taken once when the machine starts from idle. */
        public Builder startupEuPerRecipe(@Nonnull ToLongBiFunction<Inputs, GTRecipe> eu) {
            this.startupEu = eu;
            return this;
        }

        /** EU taken when each run starts, besides EU/t. */
        public Builder euPerRunPerRecipe(@Nonnull BiFunction<Inputs, GTRecipe, BigInteger> eu) {
            this.euPerRun = eu;
            return this;
        }

        /** EU given when each run ends. */
        public Builder euGeneratedPerRecipe(@Nonnull BiFunction<Inputs, GTRecipe, BigInteger> eu) {
            this.euGeneratedPerRun = eu;
            return this;
        }

        /** The chance, 0 to 1, that each parallel succeeds. */
        public Builder successChancePerRecipe(@Nonnull ToDoubleBiFunction<Inputs, GTRecipe> chance) {
            this.successChance = chance;
            return this;
        }

        /** Multiplies the outputs of each parallel that succeeds. */
        public Builder outputYieldPerRecipe(@Nonnull ToDoubleBiFunction<Inputs, GTRecipe> yield) {
            this.outputYield = yield;
            return this;
        }

        public Builder customTooltip(@Nonnull Quantity quantity, @Nonnull Consumer<MultiblockTooltipBuilder> lines) {
            this.customTooltips.put(quantity, lines);
            return this;
        }

        /** One block of lines for several quantities, written once where the first of them would be. */
        public Builder customTooltip(@Nonnull Set<Quantity> quantities,
            @Nonnull Consumer<MultiblockTooltipBuilder> lines) {
            for (Quantity quantity : quantities) this.customTooltips.put(quantity, lines);
            return this;
        }

        /** For numbers the tooltip covers elsewhere, or not at all. */
        public Builder noTooltip(@Nonnull Quantity... quantities) {
            Collections.addAll(this.noTooltip, quantities);
            return this;
        }

        /** For numbers the machine's code also changes. */
        public Builder alsoCustom(@Nonnull Quantity... quantities) {
            Collections.addAll(this.alsoCustom, quantities);
            return this;
        }

        /**
         * For a mode whose numbers the machine's code sets, such as a simulation that is not a recipe. The spec still
         * applies there. {@link ProcessingSpec#supportsMode} returns false for it.
         */
        public Builder unsupportedInMode(int mode) {
            this.unsupportedModes.add(mode);
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
                modes -> modes.get(mode)
                    .nameKey(),
                mode,
                terms);
        }

        /** Replaces the spec's terms at one value. The lines are headed by {@link ModifierKind#label}. */
        public Builder whenTier(@Nonnull ModifierKind<Integer> kind, int value, @Nonnull Consumer<Builder> terms) {
            return variant(in -> in.value(kind) == value, modes -> kind.label(value), null, terms);
        }

        private Builder variant(Predicate<Inputs> appliesTo, Function<List<MachineMode>, String> name,
            @Nullable Integer mode, Consumer<Builder> terms) {
            Builder variant = new Builder();
            terms.accept(variant);
            ProcessingSpec spec = variant.build();
            for (Quantity quantity : Quantity.values()) {
                if (spec.sets(quantity) && !SETTABLE_IN_VARIANT.contains(quantity)) {
                    throw new IllegalArgumentException(quantity + " cannot differ by mode or tier");
                }
            }
            if (!variant.alsoCustom.isEmpty() || !variant.variants.isEmpty()
                || variant.modes != null
                || !variant.unsupportedModes.isEmpty()) {
                throw new IllegalArgumentException("a mode or tier takes terms and their tooltips only");
            }
            if (spec.readsRecipe(Quantity.PARALLEL) || spec.readsRecipe(Quantity.DURATION)
                || spec.readsRecipe(Quantity.OVERCLOCK)
                || !variant.requirements.isEmpty()) {
                throw new IllegalArgumentException("terms that read the recipe, and requirements, apply in every mode");
            }
            if (spec.isNoOverclock()) throw new IllegalArgumentException("noOverclock applies in every mode");
            this.variants.add(new Variant(appliesTo, name, mode, spec));
            return this;
        }

        @Nonnull
        public ProcessingSpec build() {
            if (!heatRules.isEmpty() && heatFunction == null) {
                throw new IllegalStateException("heat rules without heat");
            }
            if (recipeHeat.isPresent() && heatFunction == null) {
                throw new IllegalStateException("recipe heat without machine heat");
            }
            for (Variant variant : variants) {
                if (variant.mode != null && (modes == null || variant.mode >= modes.size())) {
                    throw new IllegalStateException("inMode(" + variant.mode + ") without that mode in modes()");
                }
            }
            return new ProcessingSpec(this);
        }
    }
}
