package gregtech.api.logic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.function.ToDoubleBiFunction;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntBiFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongBiFunction;
import java.util.function.ToLongFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.util.EnumChatFormatting;

import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.OverclockCalculator;

/**
 * How a multiblock runs its recipes, as a function of its energy hatches, mode and {@link ModifierKind} values.
 * {@link MTEMultiBlockBase} runs a machine's recipes through the spec and writes the machine's tooltip from the spec.
 * Planners evaluate a spec without a world: {@link #getModifiers} returns the kinds the spec reads, {@link #resolve}
 * returns every number for one recipe, and {@link #calculate} runs the recipe.
 * <p>
 * Keep one per machine class in a static field, or one per instance for a machine whose constants depend on its tier.
 * Every number in a spec must reach the tooltip: every term built from a {@link Formula} has a tooltip line, and a
 * plain function requires {@link Builder#customTooltip} or {@link Builder#noTooltip}.
 */
public final class ProcessingSpec {

    /**
     * The energy a machine's recipes see.
     *
     * @param unlimited    Energy limits neither parallels nor overclocks
     * @param euPerTickCap The most EU/t a run draws, whatever its overclocks cost
     */
    public record Power(long voltage, long amperage, boolean amperageOverclock, boolean unlimited, long euPerTickCap) {

        public Power(long voltage, long amperage, boolean amperageOverclock, boolean unlimited) {
            this(voltage, amperage, amperageOverclock, unlimited, Long.MAX_VALUE);
        }

        public long availableEuPerTick() {
            return unlimited ? Long.MAX_VALUE : voltage * amperage;
        }
    }

    private record RecipeOverride(long eut, int duration) {}

    public sealed interface OverclockRule {

        /** No overclocks: each recipe runs at the recipe's EU/t and duration. */
        record None() implements OverclockRule {}

        record Ratio(double durationDivisor, double euMultiplier) implements OverclockRule {

            public static final Ratio STANDARD = new Ratio(2, 4);
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

    public record Heat(@Nonnull ToIntFunction<ProcessingInputs> machineHeat, @Nonnull Set<HeatRule> rules) {

        public int getMachineHeat(@Nonnull ProcessingInputs inputs) {
            return machineHeat.applyAsInt(inputs);
        }

        public int getRecipeHeat(@Nonnull GTRecipe recipe) {
            return recipe.mSpecialValue;
        }
    }

    /** In tooltip order. */
    public enum Quantity {

        PARALLEL(true),
        /** The formula of this quantity returns a speed, set with {@link Builder#speed}. */
        DURATION(true),
        EU_MODIFIER(true),
        EU_MODIFIER_NOT_LIMITING_PARALLEL(true),
        /** Multiplies the recipe's EU/t, before overclocks and before its voltage is checked. */
        RECIPE_EU_MULTIPLIER(true),
        OVERCLOCK(true),
        TIER_SKIPS(false),
        HEAT(false),
        RECIPE_OVERRIDE(false),
        /** The voltage, the amperage, amperage overclocks, the EU/t cap and EU taken or given once per start or run. */
        POWER(false),
        /** Success chance and yield. */
        OUTPUT(false);

        private final boolean settableInVariant;

        Quantity(boolean settableInVariant) {
            this.settableInVariant = settableInVariant;
        }

        private boolean isSettableInVariant() {
            return settableInVariant;
        }
    }

    private static final ToIntFunction<ProcessingInputs> COIL_HEAT = in -> (int) HeatingCoilLevel
        .getFromTier((byte) in.value(ModifierKind.COIL))
        .getHeat();

    private static final ToLongFunction<ProcessingInputs> STANDARD_VOLTAGE = ProcessingInputs::averageVoltage;
    private static final ToLongFunction<ProcessingInputs> STANDARD_AMPERAGE = in -> in.isSingleRegularHatch() ? 1
        : in.amperage();

    /** {@code lines} is null for a {@link Formula.Custom} formula. */
    private record Term(Formula formula, @Nullable Consumer<MultiblockTooltipBuilder> lines) {}

    /** Exactly one of {@code term} and {@code perRecipe}. */
    private record ParallelTerm(@Nullable Term term, @Nullable ToIntBiFunction<ProcessingInputs, GTRecipe> perRecipe) {}

    private record Requirement(BiPredicate<ProcessingInputs, GTRecipe> met,
        BiFunction<ProcessingInputs, GTRecipe, CheckRecipeResult> failure) {}

    /** @param label Heads a {@link Builder#whenTier} variant's tooltip lines. Null for a mode's. */
    private record Variant(Predicate<ProcessingInputs> appliesTo, @Nullable String label, @Nullable Integer mode,
        ProcessingSpec terms) {}

    /** {@code complete} is false if a term of the quantity has no lines. */
    private record Described(boolean readsRecipe, List<Consumer<MultiblockTooltipBuilder>> lines, boolean complete) {}

    private final List<ParallelTerm> parallel;
    /** The quantities with a single formula: the speed, the two EU modifiers and the recipe EU/t multiplier. */
    private final EnumMap<Quantity, Term> scalars;
    @Nullable
    private final OverclockRule overclock;
    private final OptionalInt maxTierSkips;
    @Nullable
    private final Heat heat;
    @Nullable
    private final ToIntBiFunction<ProcessingInputs, GTRecipe> recipeDuration;
    @Nullable
    private final ToIntBiFunction<ProcessingInputs, GTRecipe> maxOverclocks;
    private final List<Requirement> requirements;
    private final List<Requirement> startRequirements;
    @Nullable
    private final RecipeOverride recipeOverride;
    @Nullable
    private final ToLongFunction<ProcessingInputs> voltage;
    @Nullable
    private final ToLongFunction<ProcessingInputs> amperage;
    private final boolean noAmperageOverclock;
    @Nullable
    private final Predicate<ProcessingInputs> unlimitedEnergy;
    @Nullable
    private final ToLongFunction<ProcessingInputs> euPerTickCap;
    @Nullable
    private final ToLongBiFunction<ProcessingInputs, GTRecipe> startupEu;
    @Nullable
    private final BiFunction<ProcessingInputs, GTRecipe, BigInteger> euPerRun;
    @Nullable
    private final BiFunction<ProcessingInputs, GTRecipe, BigInteger> euGeneratedPerRun;
    @Nullable
    private final ToDoubleBiFunction<ProcessingInputs, GTRecipe> successChance;
    @Nullable
    private final ToDoubleBiFunction<ProcessingInputs, GTRecipe> outputYield;
    private final EnumMap<Quantity, Described> described;
    private final Map<Quantity, Consumer<MultiblockTooltipBuilder>> customTooltips;
    private final Set<Quantity> noTooltip;
    private final Map<ModifierKind, ModifierKind.Range> modifiers;
    private final List<Variant> variants;
    private final List<MachineMode> modes;
    private final Set<Integer> notCalculatedModes;

    /** The builder's settings. The heat requirement is checked before the builder's requirements. */
    private ProcessingSpec(Builder builder, Map<ModifierKind, ModifierKind.Range> modifiers) {
        this.parallel = List.copyOf(builder.parallel);
        this.scalars = new EnumMap<>(builder.scalars);
        this.overclock = builder.overclock;
        this.maxTierSkips = builder.maxTierSkips;
        Heat heat = builder.heatFunction == null ? null
            : new Heat(builder.heatFunction, Collections.unmodifiableSet(EnumSet.copyOf(builder.heatRules)));
        this.heat = heat;
        this.recipeDuration = builder.recipeDuration;
        this.maxOverclocks = builder.maxOverclocks;
        List<Requirement> requirements = new ArrayList<>();
        if (heat != null && heat.rules()
            .contains(HeatRule.REQUIRED)) {
            requirements.add(
                new Requirement(
                    (in, recipe) -> heat.getRecipeHeat(recipe) <= heat.getMachineHeat(in),
                    (in, recipe) -> CheckRecipeResultRegistry.insufficientHeat(heat.getRecipeHeat(recipe))));
        }
        requirements.addAll(builder.requirements);
        this.requirements = List.copyOf(requirements);
        this.startRequirements = List.copyOf(builder.startRequirements);
        this.recipeOverride = builder.recipeOverride;
        this.voltage = builder.voltage;
        this.amperage = builder.amperage;
        this.noAmperageOverclock = builder.noAmperageOverclock;
        this.unlimitedEnergy = builder.unlimitedEnergy;
        this.euPerTickCap = builder.euPerTickCap;
        this.startupEu = builder.startupEu;
        this.euPerRun = builder.euPerRun;
        this.euGeneratedPerRun = builder.euGeneratedPerRun;
        this.successChance = builder.successChance;
        this.outputYield = builder.outputYield;
        this.described = describeQuantities(builder, heat);
        this.customTooltips = new EnumMap<>(builder.customTooltips);
        this.noTooltip = builder.noTooltip.clone();
        this.modifiers = modifiers;
        this.variants = List.copyOf(builder.variants);
        this.modes = builder.modes == null ? List.of() : List.copyOf(builder.modes);
        this.notCalculatedModes = Set.copyOf(builder.notCalculatedModes);
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** The kinds the spec reads besides energy and mode, in declaration order, with the values a machine takes. */
    @Nonnull
    public Map<ModifierKind, ModifierKind.Range> getModifiers() {
        return modifiers;
    }

    /** @throws IllegalArgumentException if the spec does not read the kind */
    @Nonnull
    public ModifierKind.Range getRange(@Nonnull ModifierKind kind) {
        ModifierKind.Range range = modifiers.get(kind);
        if (range == null) throw new IllegalArgumentException("the spec does not read " + kind);
        return range;
    }

    /**
     * The value of {@code kind} at which the machine is best: the max of an {@link ModifierKind#ordered} kind, the min
     * of any other.
     *
     * @throws IllegalArgumentException if the spec does not read the kind
     */
    public long bestValue(@Nonnull ModifierKind kind) {
        ModifierKind.Range range = getRange(kind);
        return kind.ordered ? range.max() : range.min();
    }

    /** Inputs with every kind at its {@link #bestValue}. Add the energy hatches and the mode. */
    @Nonnull
    public ProcessingInputs.Builder bestInputs() {
        ProcessingInputs.Builder inputs = ProcessingInputs.builder();
        for (ModifierKind kind : modifiers.keySet()) inputs.value(kind, bestValue(kind));
        return inputs;
    }

    public boolean variesByMode() {
        for (Variant variant : variants) if (variant.mode != null) return true;
        return false;
    }

    @Nonnull
    public List<MachineMode> getModes() {
        return modes;
    }

    /** False in a mode the machine runs outside recipe processing. {@link #resolve} throws there. */
    public boolean calculates(int mode) {
        return !notCalculatedModes.contains(mode);
    }

    /**
     * The lowest value of an ordered {@code kind} that passes the recipe's requirements, such as the lowest coil tier
     * hot enough for the recipe. The search is binary. A higher value of an ordered kind never fails a requirement that
     * a lower value passes.
     *
     * @throws IllegalArgumentException if the kind is not {@link ModifierKind#ordered}
     */
    @Nonnull
    public OptionalInt lowestPassing(@Nonnull ModifierKind.IntKind kind, @Nonnull GTRecipe recipe,
        @Nonnull ProcessingInputs inputs) {
        if (!kind.ordered) throw new IllegalArgumentException(kind + " is not ordered");
        ModifierKind.Range range = getRange(kind);
        long low = range.min();
        long high = range.max();
        LongPredicate passes = value -> {
            ProcessingInputs candidate = inputs.toBuilder()
                .value(kind, (int) value)
                .build();
            return check(recipeAsRun(recipe, candidate), candidate).wasSuccessful();
        };
        if (!passes.test(high)) return OptionalInt.empty();
        while (low < high) {
            long middle = low + (high - low) / 2;
            if (passes.test(middle)) high = middle;
            else low = middle + 1;
        }
        return OptionalInt.of((int) low);
    }

    /**
     * @throws IllegalArgumentException if {@link #calculates} is false for the mode, or if the inputs lack a kind the
     *                                  spec reads
     */
    @Nonnull
    public ResolvedRecipe resolve(@Nonnull GTRecipe recipe, @Nonnull ProcessingInputs inputs) {
        if (!calculates(inputs.mode())) {
            throw new IllegalArgumentException("the spec does not calculate mode " + inputs.mode());
        }
        return resolveInAnyMode(recipe, inputs);
    }

    /** As {@link #resolve}, also in a mode the spec does not calculate, for the recipe a machine runs there. */
    @Nonnull
    ResolvedRecipe resolveInAnyMode(@Nonnull GTRecipe recipe, @Nonnull ProcessingInputs inputs) {
        for (ModifierKind kind : modifiers.keySet()) {
            if (!inputs.has(kind)) throw new IllegalArgumentException("no " + kind + " value given");
        }
        GTRecipe run = recipeAsRun(recipe, inputs);
        return new ResolvedRecipe(
            run,
            recipeDuration == null ? run.mDuration : recipeDuration.applyAsInt(inputs, run),
            getPower(inputs),
            getMaxParallel(inputs, run),
            getDurationMultiplier(inputs),
            getEuModifier(inputs),
            getEuModifierNotLimitingParallel(inputs),
            new ResolvedRecipe.Overclock(
                getOverclock(inputs),
                maxOverclocks == null ? OptionalInt.empty() : OptionalInt.of(maxOverclocks.applyAsInt(inputs, run)),
                getMaxTierSkipsOrDefault(),
                heat == null ? null
                    : new ResolvedRecipe.Heat(
                        heat.getMachineHeat(inputs),
                        heat.getRecipeHeat(run),
                        heat.rules()
                            .contains(HeatRule.OVERCLOCK),
                        heat.rules()
                            .contains(HeatRule.DISCOUNT))),
            check(run, inputs),
            firstFailing(startRequirements, run, inputs),
            new ProcessingRun.RunEu(
                BigInteger.valueOf(startupEu == null ? 0 : startupEu.applyAsLong(inputs, run)),
                euPerRun == null ? BigInteger.ZERO : euPerRun.apply(inputs, run),
                euGeneratedPerRun == null ? BigInteger.ZERO : euGeneratedPerRun.apply(inputs, run)),
            successChance == null && outputYield == null ? ProcessingRun.Output.CERTAIN
                : new ProcessingRun.Output(
                    successChance == null ? 1 : successChance.applyAsDouble(inputs, run),
                    outputYield == null ? 1 : outputYield.applyAsDouble(inputs, run)));
    }

    /**
     * The run from idle, with unlimited inputs and output space.
     *
     * @throws IllegalArgumentException if the inputs lack a kind the spec reads
     */
    @Nonnull
    public ProcessingRun calculate(@Nonnull GTRecipe recipe, @Nonnull ProcessingInputs inputs) {
        return resolve(recipe, inputs).calculate();
    }

    /** At least 1. Leaves out the terms that read the recipe, as a display without one does. */
    public int getMaxParallel(@Nonnull ProcessingInputs inputs) {
        return maxParallel(inputs, null);
    }

    /** At least 1. */
    public int getMaxParallel(@Nonnull ProcessingInputs inputs, @Nonnull GTRecipe recipe) {
        return maxParallel(inputs, recipe);
    }

    private int maxParallel(ProcessingInputs inputs, @Nullable GTRecipe recipe) {
        int sum = 0;
        for (ParallelTerm term : resolve(inputs, spec -> spec.parallel, terms -> !terms.isEmpty())) {
            if (term.term != null) sum += (int) term.term.formula.apply(inputs);
            else if (recipe != null) sum += term.perRecipe.applyAsInt(inputs, recipe);
        }
        return Math.max(1, sum);
    }

    /** 0.5 halves recipe time. */
    public double getDurationMultiplier(@Nonnull ProcessingInputs inputs) {
        return 1 / factor(inputs, Quantity.DURATION);
    }

    public double getEuModifier(@Nonnull ProcessingInputs inputs) {
        return factor(inputs, Quantity.EU_MODIFIER);
    }

    public double getEuModifierNotLimitingParallel(@Nonnull ProcessingInputs inputs) {
        return factor(inputs, Quantity.EU_MODIFIER_NOT_LIMITING_PARALLEL);
    }

    private double getRecipeEuMultiplier(ProcessingInputs inputs) {
        return factor(inputs, Quantity.RECIPE_EU_MULTIPLIER);
    }

    /** {@link OverclockRule.Ratio#STANDARD} where neither the spec nor a matching variant sets one. */
    @Nonnull
    public OverclockRule getOverclock(@Nonnull ProcessingInputs inputs) {
        OverclockRule rule = resolve(inputs, spec -> spec.overclock, Objects::nonNull);
        return rule == null ? OverclockRule.Ratio.STANDARD : rule;
    }

    /** False after {@link Builder#noOverclock}, in every mode and tier. */
    public boolean overclocks() {
        return !(overclock instanceof OverclockRule.None);
    }

    /** {@link Integer#MAX_VALUE} for unlimited. */
    public int getMaxTierSkipsOrDefault() {
        return maxTierSkips.orElse(OverclockCalculator.DEFAULT_MAX_TIER_SKIPS);
    }

    @Nonnull
    public Optional<Heat> getHeat() {
        return Optional.ofNullable(heat);
    }

    /** @throws IllegalStateException if the spec has no heat */
    public int getMachineHeat(@Nonnull ProcessingInputs inputs) {
        if (heat == null) throw new IllegalStateException("the spec has no heat");
        return heat.getMachineHeat(inputs);
    }

    @Nonnull
    public Power getPower(@Nonnull ProcessingInputs inputs) {
        return new Power(
            (voltage == null ? STANDARD_VOLTAGE : voltage).applyAsLong(inputs),
            (amperage == null ? STANDARD_AMPERAGE : amperage).applyAsLong(inputs),
            !noAmperageOverclock,
            unlimitedEnergy != null && unlimitedEnergy.test(inputs),
            euPerTickCap == null ? Long.MAX_VALUE : euPerTickCap.applyAsLong(inputs));
    }

    /**
     * Whether the machine can run the recipe at these inputs: the first requirement the recipe fails, heat first, else
     * success.
     */
    @Nonnull
    public CheckRecipeResult check(@Nonnull GTRecipe recipe, @Nonnull ProcessingInputs inputs) {
        return firstFailing(requirements, recipe, inputs);
    }

    private static CheckRecipeResult firstFailing(List<Requirement> requirements, GTRecipe recipe,
        ProcessingInputs inputs) {
        for (Requirement requirement : requirements) {
            if (!requirement.met.test(inputs, recipe)) return requirement.failure.apply(inputs, recipe);
        }
        return CheckRecipeResultRegistry.SUCCESSFUL;
    }

    private GTRecipe recipeAsRun(GTRecipe recipe, ProcessingInputs inputs) {
        double euMultiplier = getRecipeEuMultiplier(inputs);
        if (recipeOverride == null && euMultiplier == 1) return recipe;
        GTRecipe copy = recipe.copy();
        // a cached copy would be multiplied again at the next check
        copy.mCanBeBuffered = false;
        if (recipeOverride != null) {
            copy.mEUt = GTUtility.safeInt(recipeOverride.eut(), 0);
            copy.mDuration = recipeOverride.duration();
        }
        if (euMultiplier != 1) copy.mEUt = (int) Math.min((long) (copy.mEUt * euMultiplier), Integer.MAX_VALUE);
        return copy;
    }

    /** 1 if the spec has no term. */
    private double factor(ProcessingInputs inputs, Quantity quantity) {
        Term term = scalar(inputs, quantity);
        return term == null ? 1 : term.formula.apply(inputs);
    }

    @Nullable
    private Term scalar(ProcessingInputs inputs, Quantity quantity) {
        return resolve(inputs, spec -> spec.scalars.get(quantity), Objects::nonNull);
    }

    /** The first matching variant's setting, else the spec's. */
    private <T> T resolve(ProcessingInputs inputs, Function<ProcessingSpec, T> setting, Predicate<T> isSet) {
        for (Variant variant : variants) {
            T variantSetting = setting.apply(variant.terms);
            if (isSet.test(variantSetting) && variant.appliesTo.test(inputs)) return variantSetting;
        }
        return setting.apply(this);
    }

    private boolean sets(Quantity quantity) {
        if (described.containsKey(quantity)) return true;
        for (Variant variant : variants) if (variant.terms.sets(quantity)) return true;
        return false;
    }

    /** Whether the spec's number for this quantity depends on the recipe. */
    private boolean readsRecipe(Quantity quantity) {
        Described quantityDescribed = described.get(quantity);
        return quantityDescribed != null && quantityDescribed.readsRecipe;
    }

    /** The quantities the spec sets with no tooltip line. */
    @Nonnull
    public Set<Quantity> getUndescribed() {
        Set<Quantity> overridden = overridden();
        EnumSet<Quantity> undescribed = EnumSet.noneOf(Quantity.class);
        for (Quantity quantity : Quantity.values()) {
            if (overridden.contains(quantity)) continue;
            if (!isComplete(quantity)) undescribed.add(quantity);
            for (Variant variant : variants) {
                if (!variant.terms.overridden()
                    .contains(quantity) && !variant.terms.isComplete(quantity)) undescribed.add(quantity);
            }
        }
        return undescribed;
    }

    private boolean isComplete(Quantity quantity) {
        Described quantityDescribed = described.get(quantity);
        return quantityDescribed == null || quantityDescribed.complete;
    }

    /** Writes the lines in {@link Quantity} order, then each {@link Builder#whenTier} variant's under its label. */
    public void describe(@Nonnull MultiblockTooltipBuilder tt) {
        writeLines(tt, EnumSet.noneOf(Quantity.class));
        for (Variant variant : variants) if (variant.mode == null) writeHeaded(tt, variant);
    }

    /** Writes the {@link Builder#inMode} lines for one mode without its name, for that mode's tooltip section. */
    public void describeMode(@Nonnull MultiblockTooltipBuilder tt, @Nonnull MachineMode mode) {
        int index = modes.indexOf(mode);
        boolean any = false;
        for (Variant variant : variants) {
            if (variant.mode == null || variant.mode != index) continue;
            any = true;
            variant.terms.writeLines(tt, overridden());
        }
        if (!any) throw new IllegalArgumentException("no inMode terms for " + mode.nameKey());
    }

    private void writeHeaded(MultiblockTooltipBuilder tt, Variant variant) {
        MultiblockTooltipBuilder lines = new MultiblockTooltipBuilder();
        variant.terms.writeLines(lines, overridden());
        tt.addLinesFrom(EnumChatFormatting.WHITE + variant.label + EnumChatFormatting.GRAY + ": ", lines);
    }

    private EnumSet<Quantity> overridden() {
        EnumSet<Quantity> overridden = EnumSet.copyOf(noTooltip);
        overridden.addAll(customTooltips.keySet());
        return overridden;
    }

    private void writeLines(MultiblockTooltipBuilder tt, Set<Quantity> skip) {
        Set<Consumer<MultiblockTooltipBuilder>> customWritten = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Quantity quantity : Quantity.values()) {
            if (skip.contains(quantity) || noTooltip.contains(quantity)) continue;
            Consumer<MultiblockTooltipBuilder> custom = customTooltips.get(quantity);
            if (custom != null) {
                // one customTooltip can cover several quantities
                if (customWritten.add(custom)) custom.accept(tt);
                continue;
            }
            Described quantityDescribed = described.get(quantity);
            if (quantityDescribed != null) quantityDescribed.lines.forEach(line -> line.accept(tt));
        }
    }

    /** Each quantity's tooltip lines, from the builder's settings. */
    private static EnumMap<Quantity, Described> describeQuantities(Builder builder, @Nullable Heat heat) {
        EnumMap<Quantity, Described> described = new EnumMap<>(Quantity.class);
        if (!builder.parallel.isEmpty()) {
            List<Consumer<MultiblockTooltipBuilder>> lines = new ArrayList<>();
            boolean complete = true;
            boolean perRecipe = false;
            for (ParallelTerm term : builder.parallel) {
                perRecipe |= term.perRecipe != null;
                if (term.term == null || term.term.lines == null) complete = false;
                else lines.add(term.term.lines);
            }
            described.put(Quantity.PARALLEL, new Described(perRecipe, lines, complete));
        }
        builder.scalars.forEach((quantity, term) -> {
            boolean perRecipe = quantity == Quantity.DURATION && builder.recipeDuration != null;
            described.put(
                quantity,
                new Described(
                    perRecipe,
                    term.lines == null ? List.of() : List.of(term.lines),
                    term.lines != null && !perRecipe));
        });
        if (builder.recipeDuration != null && !builder.scalars.containsKey(Quantity.DURATION)) {
            described.put(Quantity.DURATION, new Described(true, List.of(), false));
        }
        if (builder.overclock != null || builder.maxOverclocks != null) {
            List<Consumer<MultiblockTooltipBuilder>> lines = new ArrayList<>();
            if (builder.overclock instanceof OverclockRule.Ratio ratio && !ratio.equals(OverclockRule.Ratio.STANDARD)) {
                lines.add(tt -> tt.addOverclockRatioInfo(ratio.durationDivisor(), ratio.euMultiplier()));
            }
            if (builder.maxOverclocksTooltip != null) lines.add(builder.maxOverclocksTooltip);
            described.put(
                Quantity.OVERCLOCK,
                new Described(
                    builder.maxOverclocks != null,
                    lines,
                    builder.maxOverclocks == null || builder.maxOverclocksTooltip != null));
        }
        if (builder.maxTierSkips.isPresent()) {
            int skips = builder.maxTierSkips.getAsInt();
            List<Consumer<MultiblockTooltipBuilder>> lines = skips == Integer.MAX_VALUE
                ? List.of(MultiblockTooltipBuilder::addUnlimitedTierSkips)
                : skips > 1 ? List.of(tt -> tt.addMaxTierSkips(skips)) : List.of();
            described.put(Quantity.TIER_SKIPS, new Described(false, lines, true));
        }
        if (heat != null) {
            List<Consumer<MultiblockTooltipBuilder>> lines = new ArrayList<>();
            lines.add(builder.heatTooltip);
            if (heat.rules()
                .contains(HeatRule.DISCOUNT)) lines.add(MultiblockTooltipBuilder::addHeatDiscountInfo);
            if (heat.rules()
                .contains(HeatRule.OVERCLOCK)) lines.add(MultiblockTooltipBuilder::addHeatOverclockInfo);
            described.put(Quantity.HEAT, new Described(true, lines, true));
        }
        if (builder.recipeOverride != null) {
            RecipeOverride override = builder.recipeOverride;
            described.put(
                Quantity.RECIPE_OVERRIDE,
                new Described(
                    true,
                    List.of(tt -> tt.addRecipeOverrideInfo(override.eut(), override.duration())),
                    true));
        }
        boolean powerReadsRecipe = builder.startupEu != null || builder.euPerRun != null
            || builder.euGeneratedPerRun != null
            || !builder.startRequirements.isEmpty();
        if (powerReadsRecipe || builder.voltage != null
            || builder.amperage != null
            || builder.noAmperageOverclock
            || builder.unlimitedEnergy != null
            || builder.euPerTickCap != null) {
            described.put(Quantity.POWER, new Described(powerReadsRecipe, List.of(), false));
        }
        if (builder.successChance != null || builder.outputYield != null) {
            described.put(Quantity.OUTPUT, new Described(true, List.of(), false));
        }
        return described;
    }

    public static final class Builder {

        private final List<ParallelTerm> parallel = new ArrayList<>();
        private final EnumMap<Quantity, Term> scalars = new EnumMap<>(Quantity.class);
        private OverclockRule overclock;
        private OptionalInt maxTierSkips = OptionalInt.empty();
        private ToIntFunction<ProcessingInputs> heatFunction;
        private Consumer<MultiblockTooltipBuilder> heatTooltip;
        private final EnumSet<HeatRule> heatRules = EnumSet.noneOf(HeatRule.class);
        private RecipeOverride recipeOverride;
        private final EnumMap<Quantity, Consumer<MultiblockTooltipBuilder>> customTooltips = new EnumMap<>(
            Quantity.class);
        private final EnumSet<Quantity> noTooltip = EnumSet.noneOf(Quantity.class);
        private List<MachineMode> modes;
        private final List<Variant> variants = new ArrayList<>();
        private final Set<Integer> notCalculatedModes = new HashSet<>();
        private ToIntBiFunction<ProcessingInputs, GTRecipe> recipeDuration;
        private ToIntBiFunction<ProcessingInputs, GTRecipe> maxOverclocks;
        private Consumer<MultiblockTooltipBuilder> maxOverclocksTooltip;
        private final List<Requirement> requirements = new ArrayList<>();
        private final List<Requirement> startRequirements = new ArrayList<>();
        private ToLongFunction<ProcessingInputs> voltage;
        private ToLongFunction<ProcessingInputs> amperage;
        private boolean noAmperageOverclock;
        private Predicate<ProcessingInputs> unlimitedEnergy;
        private ToLongFunction<ProcessingInputs> euPerTickCap;
        private ToLongBiFunction<ProcessingInputs, GTRecipe> startupEu;
        private BiFunction<ProcessingInputs, GTRecipe, BigInteger> euPerRun;
        private BiFunction<ProcessingInputs, GTRecipe, BigInteger> euGeneratedPerRun;
        private ToDoubleBiFunction<ProcessingInputs, GTRecipe> successChance;
        private ToDoubleBiFunction<ProcessingInputs, GTRecipe> outputYield;
        /** A null range means the kind's range. */
        private final Map<ModifierKind, ModifierKind.Range> reads = new LinkedHashMap<>();

        private Builder() {}

        /** For kinds that plain functions read. {@link #reading} adds a {@link Formula}'s kinds. */
        public Builder reads(@Nonnull ModifierKind... kinds) {
            for (ModifierKind kind : kinds) {
                if (kind != ModifierKind.VOLTAGE) this.reads.putIfAbsent(kind, null);
            }
            return this;
        }

        /** Reads the kind, at a range for this machine. */
        public Builder range(@Nonnull ModifierKind kind, long min, long max) {
            this.reads.put(kind, new ModifierKind.Range(min, max));
            return this;
        }

        private Formula reading(Formula formula) {
            reads(
                formula.reads()
                    .toArray(new ModifierKind[0]));
            return formula;
        }

        /** Parallel terms add up. */
        public Builder parallel(int parallel) {
            return parallelTerm(new Formula.Constant(parallel), tt -> tt.addStaticParallelInfo(parallel));
        }

        /** Read on every use, such as a config value. */
        public Builder parallel(@Nonnull IntSupplier parallel) {
            return parallelTerm(
                new Formula.Supplied(parallel::getAsInt),
                tt -> tt.addStaticParallelInfo(parallel.getAsInt()));
        }

        public Builder parallelPerTier(int parallel, @Nonnull ModifierKind.IntKind kind) {
            return parallelTerm(
                new Formula.PerTier(0, parallel, kind),
                tt -> tt.addDynamicParallelInfo(parallel, kind));
        }

        /** {@code parallel} times {@code factor} per tier of {@code kind}: 4 and 2 is 8 at the first tier. */
        public Builder parallelCompoundPerTier(int parallel, int factor, @Nonnull ModifierKind.IntKind kind) {
            return parallelTerm(
                new Formula.CompoundPerTier(parallel * factor, factor, kind),
                tt -> tt.addStaticParallelInfo(parallel)
                    .addDynamicMultiplicativeParallelInfo(factor, kind));
        }

        /** From {@code min} to {@code max} per voltage tier as {@code kind} rises from 0 to its range's max. */
        public Builder parallelPerVoltageTierRising(int min, int max, @Nonnull ModifierKind.IntKind kind) {
            return parallelTerm(
                new Formula.RisingPerVoltageTier(min, max, kind, (int) rangeOf(kind).max()),
                tt -> tt.addRisingParallelPerVoltageTierInfo(min, max, kind));
        }

        /** Counts ULV as LV. {@code parallelPerTier(parallel, VOLTAGE)} counts ULV as 0. */
        public Builder parallelPerVoltageTier(int parallel) {
            return parallelTerm(new Formula.PerVoltageTier(parallel), tt -> tt.addVoltageParallelInfo(parallel));
        }

        public Builder parallel(@Nonnull ToIntFunction<ProcessingInputs> parallel, @Nonnull ModifierKind... reads) {
            return parallelTerm(new Formula.Custom(parallel::applyAsInt, Set.of(reads)), null);
        }

        /** Adds a parallel that depends on the recipe. */
        public Builder parallelPerRecipe(@Nonnull ToIntBiFunction<ProcessingInputs, GTRecipe> parallel) {
            this.parallel.add(new ParallelTerm(null, parallel));
            return this;
        }

        private Builder parallelTerm(Formula formula, @Nullable Consumer<MultiblockTooltipBuilder> lines) {
            this.parallel.add(new ParallelTerm(new Term(reading(formula), lines), null));
            return this;
        }

        /** 2.5 is 250% speed: recipes take 1 / 2.5 of the time. */
        public Builder speed(double speed) {
            return scalar(Quantity.DURATION, new Formula.Constant(speed), tt -> tt.addStaticSpeedInfo((float) speed));
        }

        public Builder speed(@Nonnull ToDoubleFunction<ProcessingInputs> speed, @Nonnull ModifierKind... reads) {
            return scalar(Quantity.DURATION, new Formula.Custom(speed, Set.of(reads)), null);
        }

        /** {@code base} plus {@code perTier} per tier of {@code kind}: 1 and 1 is 200% at the first tier. */
        public Builder speedPerTier(double base, double perTier, @Nonnull ModifierKind.IntKind kind) {
            return scalar(
                Quantity.DURATION,
                new Formula.PerTier(base, perTier, kind),
                tt -> tt.addSpeedPerTierInfo((float) base, (float) perTier, kind));
        }

        /** From {@code min} to {@code max} speed as {@code kind} rises from 0 to its range's max. */
        public Builder speedRising(double min, double max, @Nonnull ModifierKind.IntKind kind) {
            return scalar(
                Quantity.DURATION,
                new Formula.Rising(min, max, kind, (int) rangeOf(kind).max()),
                tt -> tt.addRisingSpeedInfo((float) min, (float) max, kind));
        }

        private static ModifierKind.Range rangeOf(ModifierKind.IntKind kind) {
            ModifierKind.Range range = kind.getRange();
            if (range == null)
                throw new IllegalArgumentException(kind + " has no range. Give the kind one with range()");
            return range;
        }

        public Builder euModifier(double euModifier) {
            return scalar(
                Quantity.EU_MODIFIER,
                new Formula.Constant(euModifier),
                tt -> tt.addStaticEuEffInfo((float) euModifier));
        }

        public Builder euModifier(@Nonnull ToDoubleFunction<ProcessingInputs> euModifier,
            @Nonnull ModifierKind... reads) {
            return scalar(Quantity.EU_MODIFIER, new Formula.Custom(euModifier, Set.of(reads)), null);
        }

        /** Multiplies the recipe's EU/t, up to {@link Integer#MAX_VALUE}, as if the recipe were costlier. */
        public Builder recipeEuMultiplier(double multiplier) {
            return scalar(
                Quantity.RECIPE_EU_MULTIPLIER,
                new Formula.Constant(multiplier),
                tt -> tt.addRecipeEuMultiplierInfo(multiplier));
        }

        public Builder euModifierNotLimitingParallel(@Nonnull ToDoubleFunction<ProcessingInputs> euModifier,
            @Nonnull ModifierKind... reads) {
            return scalar(
                Quantity.EU_MODIFIER_NOT_LIMITING_PARALLEL,
                new Formula.Custom(euModifier, Set.of(reads)),
                null);
        }

        private Builder scalar(Quantity quantity, Formula formula, @Nullable Consumer<MultiblockTooltipBuilder> lines) {
            scalars.put(quantity, new Term(reading(formula), lines));
            return this;
        }

        public Builder noOverclock() {
            this.overclock = new OverclockRule.None();
            return this;
        }

        public Builder overclock(double durationDivisor, double euMultiplier) {
            this.overclock = new OverclockRule.Ratio(durationDivisor, euMultiplier);
            return this;
        }

        /** Values of 1 and below have no tooltip line. */
        public Builder maxTierSkips(int tierSkips) {
            this.maxTierSkips = OptionalInt.of(tierSkips);
            return this;
        }

        public Builder unlimitedTierSkips() {
            return maxTierSkips(Integer.MAX_VALUE);
        }

        /** The recipe's duration in ticks before overclocks, replacing the recipe's. */
        public Builder durationPerRecipe(@Nonnull ToIntBiFunction<ProcessingInputs, GTRecipe> ticks) {
            this.recipeDuration = ticks;
            return this;
        }

        /** Caps the overclocks per recipe. */
        public Builder maxOverclocksPerRecipe(@Nonnull ToIntBiFunction<ProcessingInputs, GTRecipe> maxOverclocks) {
            this.maxOverclocks = maxOverclocks;
            return this;
        }

        public Builder maxOverclocksPerRecipe(@Nonnull ToIntBiFunction<ProcessingInputs, GTRecipe> maxOverclocks,
            @Nonnull Consumer<MultiblockTooltipBuilder> tooltip) {
            this.maxOverclocksTooltip = tooltip;
            return maxOverclocksPerRecipe(maxOverclocks);
        }

        /** Coil heat, plus {@code heatPerTier} K for every voltage tier past {@code baseVoltageTier}. */
        public Builder coilHeatPerVoltageTier(int heatPerTier, int baseVoltageTier, @Nonnull HeatRule... rules) {
            this.heatFunction = in -> COIL_HEAT.applyAsInt(in) + heatPerTier * (in.voltageTier() - baseVoltageTier);
            Collections.addAll(this.heatRules, rules);
            reads(ModifierKind.COIL);
            this.heatTooltip = tt -> tt.addHeatPerVoltageTierInfo(heatPerTier, baseVoltageTier);
            return this;
        }

        /** Replaces every recipe's cost. */
        public Builder recipeOverride(long eut, int duration) {
            this.recipeOverride = new RecipeOverride(eut, duration);
            return this;
        }

        /** The check returns the first unmet requirement's {@code failure}, in declaration order. */
        public Builder requires(@Nonnull BiPredicate<ProcessingInputs, GTRecipe> met,
            @Nonnull BiFunction<ProcessingInputs, GTRecipe, CheckRecipeResult> failure) {
            this.requirements.add(new Requirement(met, failure));
            return this;
        }

        /**
         * Replaces the standard power: the average hatch voltage, and all amps except that only 1 A of a lone regular
         * hatch is used.
         */
        public Builder power(@Nonnull ToLongFunction<ProcessingInputs> voltage,
            @Nonnull ToLongFunction<ProcessingInputs> amperage) {
            this.voltage = voltage;
            this.amperage = amperage;
            return this;
        }

        /** All hatch EU as one amp. */
        public Builder powerAtOneAmp() {
            return power(ProcessingInputs::totalEu, in -> 1);
        }

        /** Uses both amps of a lone regular hatch too. */
        public Builder allAmps() {
            return power(STANDARD_VOLTAGE, ProcessingInputs::amperage);
        }

        /** With extra amps, only the parallels rise, not the overclocks. */
        public Builder noAmperageOverclock() {
            this.noAmperageOverclock = true;
            return this;
        }

        /** The most EU/t a run draws, whatever its overclocks cost. */
        public Builder euPerTickCap(@Nonnull ToLongFunction<ProcessingInputs> euPerTickCap) {
            this.euPerTickCap = euPerTickCap;
            return this;
        }

        /**
         * Where {@code when} is true, energy limits neither parallels nor overclocks. The recipe's voltage check still
         * reads the voltage.
         */
        public Builder unlimitedEnergy(@Nonnull Predicate<ProcessingInputs> when) {
            this.unlimitedEnergy = when;
            return this;
        }

        /** A requirement to start from idle, such as enough power to ignite. Part of {@link Quantity#POWER}. */
        public Builder requiresToStart(@Nonnull BiPredicate<ProcessingInputs, GTRecipe> met,
            @Nonnull BiFunction<ProcessingInputs, GTRecipe, CheckRecipeResult> failure) {
            this.startRequirements.add(new Requirement(met, failure));
            return this;
        }

        /** EU taken once when the machine starts from idle. */
        public Builder startupEuPerRecipe(@Nonnull ToLongBiFunction<ProcessingInputs, GTRecipe> eu) {
            this.startupEu = eu;
            return this;
        }

        /** EU taken when each run starts, besides EU/t. */
        public Builder euPerRunPerRecipe(@Nonnull BiFunction<ProcessingInputs, GTRecipe, BigInteger> eu) {
            this.euPerRun = eu;
            return this;
        }

        /** EU given when each run ends. */
        public Builder euGeneratedPerRecipe(@Nonnull BiFunction<ProcessingInputs, GTRecipe, BigInteger> eu) {
            this.euGeneratedPerRun = eu;
            return this;
        }

        /** The chance, 0 to 1, that each parallel succeeds. */
        public Builder successChancePerRecipe(@Nonnull ToDoubleBiFunction<ProcessingInputs, GTRecipe> chance) {
            this.successChance = chance;
            return this;
        }

        /** Multiplies the outputs of each parallel that succeeds. */
        public Builder outputYieldPerRecipe(@Nonnull ToDoubleBiFunction<ProcessingInputs, GTRecipe> yield) {
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

        /** For numbers with tooltip lines elsewhere, or with none. */
        public Builder noTooltip(@Nonnull Quantity... quantities) {
            Collections.addAll(this.noTooltip, quantities);
            return this;
        }

        /**
         * A mode the machine runs outside recipe processing, such as the Arc Furnace's ore smelting. Planners cannot
         * take numbers from the spec there: {@link ProcessingSpec#calculates} is false and
         * {@link ProcessingSpec#resolve} throws. The machine's own recipe checks still run through the spec.
         */
        public Builder notCalculatedIn(@Nonnull MachineMode mode) {
            this.notCalculatedModes.add(indexOf(mode));
            return this;
        }

        /** The machine's modes in mode button order. Call this before {@link #inMode} and {@link #notCalculatedIn}. */
        public Builder modes(@Nonnull List<MachineMode> modes) {
            Objects.requireNonNull(modes, "declare the modes before the spec");
            if (new HashSet<>(modes).size() != modes.size())
                throw new IllegalArgumentException("a mode is listed twice");
            this.modes = modes;
            return this;
        }

        /** Replaces the spec's terms in one mode. {@link ProcessingSpec#describeMode} writes their lines. */
        public Builder inMode(@Nonnull MachineMode mode, @Nonnull Consumer<Builder> terms) {
            int index = indexOf(mode);
            this.variants.add(new Variant(in -> in.mode() == index, null, index, variantTerms(terms)));
            return this;
        }

        private int indexOf(MachineMode mode) {
            if (modes == null) throw new IllegalStateException("call modes() first");
            int index = modes.indexOf(mode);
            if (index < 0) throw new IllegalArgumentException(mode.nameKey() + " is not one of modes()");
            return index;
        }

        /** Replaces the spec's terms at one value. The lines are headed by the kind's label for that value. */
        public Builder whenTier(@Nonnull ModifierKind.IntKind kind, int value, @Nonnull Consumer<Builder> terms) {
            reads(kind);
            this.variants.add(new Variant(in -> in.value(kind) == value, kind.label(value), null, variantTerms(terms)));
            return this;
        }

        /**
         * The terms of one variant, checked. The kinds they read are added to this builder.
         *
         * @throws IllegalArgumentException for a setting that must be the same in every mode and tier
         */
        private ProcessingSpec variantTerms(Consumer<Builder> terms) {
            Builder variant = new Builder();
            terms.accept(variant);
            if (!variant.variants.isEmpty() || variant.modes != null || !variant.notCalculatedModes.isEmpty()) {
                throw new IllegalArgumentException("a mode or tier takes terms and their tooltips only");
            }
            if (!variant.requirements.isEmpty()) {
                throw new IllegalArgumentException("requirements apply in every mode");
            }
            // the ranges of the kinds a variant reads are in the parent spec
            ProcessingSpec spec = variant.build(false);
            for (Quantity quantity : Quantity.values()) {
                if (spec.sets(quantity) && !quantity.isSettableInVariant()) {
                    throw new IllegalArgumentException(quantity + " cannot differ by mode or tier");
                }
            }
            if (spec.readsRecipe(Quantity.PARALLEL) || spec.readsRecipe(Quantity.DURATION)
                || spec.readsRecipe(Quantity.OVERCLOCK)) {
                throw new IllegalArgumentException("terms that read the recipe apply in every mode");
            }
            if (variant.overclock instanceof OverclockRule.None) {
                throw new IllegalArgumentException("noOverclock applies in every mode");
            }
            variant.reads.forEach(this.reads::putIfAbsent);
            return spec;
        }

        @Nonnull
        public ProcessingSpec build() {
            return build(true);
        }

        private ProcessingSpec build(boolean needsRanges) {
            Map<ModifierKind, ModifierKind.Range> modifiers = new LinkedHashMap<>();
            reads.forEach((kind, range) -> {
                ModifierKind.Range resolved = range != null ? range : kind.getRange();
                if (resolved != null) modifiers.put(kind, resolved);
                else if (needsRanges) throw new IllegalStateException(
                    "the spec reads " + kind + ", which has no range of its own; give one with range()");
            });
            return new ProcessingSpec(this, Collections.unmodifiableMap(modifiers));
        }
    }
}
