package gregtech.api.logic;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.OverclockCalculator;
import gregtech.api.util.tooltip.TooltipTier;

/**
 * What a multiblock does to the recipes it runs, as functions of its energy hatch tier, its mode and its structure. A
 * machine that returns one from {@link MTEMultiBlockBase#getProcessingSpec()} runs its {@link ProcessingLogic} from it
 * and describes it in its tooltip with {@link MultiblockTooltipBuilder#addProcessingSpecInfo}, so the numbers are
 * written once. External tools such as factory planners evaluate it with their own {@link Inputs}, without a world.
 * <p>
 * Keep one per machine class in a static field. Anything a spec leaves unset is the machine's own business: its
 * {@link ProcessingLogic} keeps whatever the machine sets, and a factory planner reads the plain default.
 * <p>
 * A number a machine only reaches while it runs, such as full momentum or a stable black hole, is declared at that
 * best case and marked with {@link Builder#bestCase}: planners assume the machine is run at its best, and the machine
 * keeps setting the number itself.
 */
public final class ProcessingSpec {

    /**
     * What a spec reads.
     *
     * @param voltageTier The energy hatch tier, as {@link gregtech.api.util.GTUtility#getTier} numbers it
     * @param mode        The machine mode, as {@link MTEMultiBlockBase#getMachineMode()} numbers it
     * @param structure   The value of each structure parameter the machine declares, in
     *                    {@link gregtech.api.structure.StructureParameter} numbering
     */
    public record Inputs(int voltageTier, int mode, @Nonnull ToIntFunction<TooltipTier> structure) {

        /** {@link TooltipTier#VOLTAGE} is the energy hatch tier; any other kind is a structure parameter. */
        public int tier(@Nonnull TooltipTier kind) {
            return kind == TooltipTier.VOLTAGE ? voltageTier : structure.applyAsInt(kind);
        }
    }

    /** A fixed recipe cost that replaces the recipe's own, such as the Multi Smelter's. */
    public record RecipeOverride(int eut, int duration) {}

    /** The numbers a spec may set, for asking whether it sets one. */
    public enum Quantity {
        PARALLEL,
        SPEED_BONUS,
        EU_MODIFIER,
        ENERGY_COST,
        OVERCLOCK
    }

    /** For a machine that runs recipes as a plain {@link ProcessingLogic} does. */
    public static final ProcessingSpec STANDARD = builder().build();

    final List<ToIntFunction<Inputs>> parallelTerms;
    @Nullable
    final ToDoubleFunction<Inputs> speedBonus;
    @Nullable
    final ToDoubleFunction<Inputs> euModifier;
    @Nullable
    private final ToDoubleFunction<Inputs> energyCost;
    final boolean overclockSet;
    private final boolean noOverclock;
    private final double overclockTimeReduction;
    private final double overclockPowerIncrease;
    final OptionalInt maxTierSkips;
    private final boolean heatOverclock;
    private final boolean heatDiscount;
    @Nullable
    private final ToIntFunction<Inputs> machineHeat;
    private final OptionalInt recipeHeat;
    @Nullable
    private final RecipeOverride recipeOverride;
    private final List<Consumer<MultiblockTooltipBuilder>> parallelInfo;
    @Nullable
    private final Consumer<MultiblockTooltipBuilder> speedInfo;
    @Nullable
    private final Consumer<MultiblockTooltipBuilder> euInfo;
    private final boolean describeOverclock;
    final EnumSet<Quantity> bestCase;

    private ProcessingSpec(Builder b) {
        this.parallelTerms = new ArrayList<>(b.parallelTerms);
        this.speedBonus = b.speedBonus;
        this.euModifier = b.euModifier;
        this.energyCost = b.energyCost;
        this.overclockSet = b.overclockSet;
        this.noOverclock = b.noOverclock;
        this.overclockTimeReduction = b.overclockTimeReduction;
        this.overclockPowerIncrease = b.overclockPowerIncrease;
        this.maxTierSkips = b.maxTierSkips;
        this.heatOverclock = b.heatOverclock;
        this.heatDiscount = b.heatDiscount;
        this.machineHeat = b.machineHeat;
        this.recipeHeat = b.recipeHeat;
        this.recipeOverride = b.recipeOverride;
        this.parallelInfo = new ArrayList<>(b.parallelInfo);
        this.speedInfo = b.speedInfo;
        this.euInfo = b.euInfo;
        this.describeOverclock = b.describeOverclock;
        this.bestCase = b.bestCase.clone();
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** The sum of the parallel terms, and at least 1. */
    public int getMaxParallel(@Nonnull Inputs inputs) {
        int parallel = 0;
        for (ToIntFunction<Inputs> term : parallelTerms) {
            parallel += term.applyAsInt(inputs);
        }
        return Math.max(1, parallel);
    }

    /** The duration multiplier, as {@link ProcessingLogic#setSpeedBonus} takes it. */
    public double getSpeedBonus(@Nonnull Inputs inputs) {
        return speedBonus == null ? 1 : speedBonus.applyAsDouble(inputs);
    }

    /** The EU/t multiplier, as {@link ProcessingLogic#setEuModifier} takes it. */
    public double getEuModifier(@Nonnull Inputs inputs) {
        return euModifier == null ? 1 : euModifier.applyAsDouble(inputs);
    }

    /**
     * The multiplier on the EU/t a recipe costs that, unlike {@link #getEuModifier}, does not count against how many
     * parallels the machine's energy allows.
     */
    public double getEnergyCost(@Nonnull Inputs inputs) {
        return energyCost == null ? 1 : energyCost.applyAsDouble(inputs);
    }

    /** Recipes run at their own voltage and are never overclocked. */
    public boolean isNoOverclock() {
        return noOverclock;
    }

    public boolean sets(@Nonnull Quantity quantity) {
        return switch (quantity) {
            case PARALLEL -> !parallelTerms.isEmpty();
            case SPEED_BONUS -> speedBonus != null;
            case EU_MODIFIER -> euModifier != null;
            case ENERGY_COST -> energyCost != null;
            case OVERCLOCK -> overclockSet || noOverclock;
        };
    }

    /** Whether the spec gives this number at the machine's best, which the machine reaches only while it runs. */
    public boolean isBestCase(@Nonnull Quantity quantity) {
        return bestCase.contains(quantity);
    }

    public double getOverclockTimeReduction() {
        return overclockTimeReduction;
    }

    public double getOverclockPowerIncrease() {
        return overclockPowerIncrease;
    }

    /** {@link Integer#MAX_VALUE} for unlimited. */
    public int getMaxTierSkips() {
        return maxTierSkips.orElse(1);
    }

    public boolean isHeatOverclock() {
        return heatOverclock;
    }

    public boolean isHeatDiscount() {
        return heatDiscount;
    }

    /** 0 for a machine without heat. */
    public int getMachineHeat(@Nonnull Inputs inputs) {
        return machineHeat == null ? 0 : machineHeat.applyAsInt(inputs);
    }

    /** The heat a recipe is overclocked against: a fixed floor if the machine has one, else the recipe's own. */
    public int getRecipeHeat(@Nonnull GTRecipe recipe) {
        return recipeHeat.orElse(recipe.mSpecialValue);
    }

    /** Empty when recipes are overclocked against their own heat. */
    @Nonnull
    public OptionalInt getFixedRecipeHeat() {
        return recipeHeat;
    }

    @Nullable
    public RecipeOverride getRecipeOverride() {
        return recipeOverride;
    }

    /** Adds the tooltip lines for everything this spec can describe: parallel, speed, EU, overclocks, tier skips. */
    public void describe(@Nonnull MultiblockTooltipBuilder tt) {
        for (Consumer<MultiblockTooltipBuilder> info : parallelInfo) {
            info.accept(tt);
        }
        if (speedInfo != null) speedInfo.accept(tt);
        if (euInfo != null) euInfo.accept(tt);
        if (describeOverclock && overclockTimeReduction == 4 && overclockPowerIncrease == 4) tt.addPerfectOCInfo();
        if (getMaxTierSkips() == Integer.MAX_VALUE) tt.addUnlimitedTierSkips();
        else if (getMaxTierSkips() > 1) tt.addMaxTierSkips(getMaxTierSkips());
    }

    public static final class Builder {

        private final List<ToIntFunction<Inputs>> parallelTerms = new ArrayList<>();
        private ToDoubleFunction<Inputs> speedBonus;
        private ToDoubleFunction<Inputs> euModifier;
        private ToDoubleFunction<Inputs> energyCost;
        private boolean overclockSet;
        private boolean noOverclock;
        private double overclockTimeReduction = 2;
        private double overclockPowerIncrease = 4;
        private OptionalInt maxTierSkips = OptionalInt.empty();
        private boolean heatOverclock;
        private boolean heatDiscount;
        private ToIntFunction<Inputs> machineHeat;
        private OptionalInt recipeHeat = OptionalInt.empty();
        private RecipeOverride recipeOverride;
        private final List<Consumer<MultiblockTooltipBuilder>> parallelInfo = new ArrayList<>();
        private Consumer<MultiblockTooltipBuilder> speedInfo;
        private Consumer<MultiblockTooltipBuilder> euInfo;
        private boolean describeOverclock;
        private final EnumSet<Quantity> bestCase = EnumSet.noneOf(Quantity.class);

        private Builder() {}

        /** Adds a fixed parallel. Parallel terms add up. */
        public Builder parallel(int parallel) {
            return parallel(in -> parallel, tt -> tt.addStaticParallelInfo(parallel));
        }

        /**
         * Adds {@code parallel} for each tier of {@code kind}; {@link TooltipTier#VOLTAGE} is the energy hatch tier.
         */
        public Builder parallelPerTier(int parallel, @Nonnull TooltipTier kind) {
            return parallel(in -> parallel * in.tier(kind), tt -> tt.addDynamicParallelInfo(parallel, kind));
        }

        /** Adds a parallel the tooltip describes itself. */
        public Builder parallel(@Nonnull ToIntFunction<Inputs> parallel) {
            return parallel(parallel, null);
        }

        /** Adds a parallel and the tooltip line that describes it. */
        public Builder parallel(@Nonnull ToIntFunction<Inputs> parallel,
            @Nullable Consumer<MultiblockTooltipBuilder> info) {
            this.parallelTerms.add(parallel);
            if (info != null) this.parallelInfo.add(info);
            return this;
        }

        /** A fixed speed as the tooltip shows it: 2.5F reads as 250% and runs recipes in 1F / 2.5F of the time. */
        public Builder speed(float speed) {
            return speedBonus(in -> 1F / speed, tt -> tt.addStaticSpeedInfo(speed));
        }

        /**
         * A duration multiplier, as {@link ProcessingLogic#setSpeedBonus} takes it, that the tooltip describes itself.
         */
        public Builder speedBonus(@Nonnull ToDoubleFunction<Inputs> durationMultiplier) {
            return speedBonus(durationMultiplier, null);
        }

        /** A duration multiplier and the tooltip line that describes it. */
        public Builder speedBonus(@Nonnull ToDoubleFunction<Inputs> durationMultiplier,
            @Nullable Consumer<MultiblockTooltipBuilder> info) {
            this.speedBonus = durationMultiplier;
            this.speedInfo = info;
            return this;
        }

        /** A fixed EU/t multiplier: 0.8F reads as 80% EU usage. */
        public Builder euModifier(float euModifier) {
            return euModifier(in -> euModifier, tt -> tt.addStaticEuEffInfo(euModifier));
        }

        /** An EU/t multiplier the tooltip describes itself. */
        public Builder euModifier(@Nonnull ToDoubleFunction<Inputs> euModifier) {
            return euModifier(euModifier, null);
        }

        /** An EU/t multiplier and the tooltip line that describes it. */
        public Builder euModifier(@Nonnull ToDoubleFunction<Inputs> euModifier,
            @Nullable Consumer<MultiblockTooltipBuilder> info) {
            this.euModifier = euModifier;
            this.euInfo = info;
            return this;
        }

        /**
         * A multiplier on the EU/t each recipe costs that, unlike {@link #euModifier}, does not limit how many
         * parallels the machine's energy allows. The tooltip describes it itself.
         */
        public Builder energyCost(@Nonnull ToDoubleFunction<Inputs> energyCost) {
            this.energyCost = energyCost;
            return this;
        }

        /** Runs recipes at their own voltage, without overclocks, as {@link OverclockCalculator#ofNoOverclock}. */
        public Builder noOverclock() {
            this.noOverclock = true;
            return this;
        }

        /**
         * Marks numbers this spec gives at the machine's best, which the machine only reaches while it runs, such as
         * at full momentum. The machine sets them itself; planners assume the best case.
         */
        public Builder bestCase(@Nonnull Quantity... quantities) {
            for (Quantity quantity : quantities) {
                if (quantity == Quantity.OVERCLOCK || quantity == Quantity.ENERGY_COST) {
                    throw new IllegalArgumentException(quantity + " cannot be a best case");
                }
                this.bestCase.add(quantity);
            }
            return this;
        }

        /** 4x speed for 4x EU/t per overclock, which the tooltip states. */
        public Builder perfectOverclock() {
            this.describeOverclock = true;
            return overclock(4, 4);
        }

        /** As {@link ProcessingLogic#setOverclock}. */
        public Builder overclock(double timeReduction, double powerIncrease) {
            this.overclockSet = true;
            this.overclockTimeReduction = timeReduction;
            this.overclockPowerIncrease = powerIncrease;
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

        /** Perfect overclocks for every 1800K the machine's heat exceeds the recipe's. */
        public Builder heatOverclock(@Nonnull ToIntFunction<Inputs> machineHeat) {
            this.heatOverclock = true;
            this.machineHeat = machineHeat;
            return this;
        }

        /** 5% less EU/t for every 900K the machine's heat exceeds the recipe's. */
        public Builder heatDiscount() {
            this.heatDiscount = true;
            return this;
        }

        /** Overclocks against this heat rather than the recipe's. */
        public Builder recipeHeat(int heat) {
            this.recipeHeat = OptionalInt.of(heat);
            return this;
        }

        /** Runs every recipe at this cost instead of its own. */
        public Builder recipeOverride(int eut, int duration) {
            this.recipeOverride = new RecipeOverride(eut, duration);
            return this;
        }

        @Nonnull
        public ProcessingSpec build() {
            if (noOverclock && overclockSet) throw new IllegalStateException("overclock ratios without overclocks");
            if ((heatOverclock || heatDiscount) && machineHeat == null) {
                throw new IllegalStateException("heat discount without a machine heat");
            }
            return new ProcessingSpec(this);
        }
    }
}
