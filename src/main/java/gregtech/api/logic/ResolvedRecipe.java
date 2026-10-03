package gregtech.api.logic;

import java.math.BigInteger;
import java.util.OptionalInt;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.OverclockCalculator;
import gregtech.api.util.ParallelHelper;

/**
 * Every number a {@link ProcessingSpec} gives one recipe at one set of inputs, before overclocks. Machines and planners
 * both calculate from it, so they cannot drift apart.
 * <p>
 * A planner overriding a single overclock setting passes its own calculator:
 * {@code resolved.calculate(resolved.toCalculator().setMaxOverclocks(3))}.
 *
 * @param recipe       As the machine runs it: a copy at the spec's fixed cost or with its EU/t multiplied, else the
 *                     recipe itself
 * @param duration     In ticks before overclocks
 * @param check        The first requirement the recipe fails, else success
 * @param checkToStart The first requirement to start from idle it fails, else success. Machines check these where they
 *                     start; {@link #calculate} checks them, since a planner starts from idle.
 */
public record ResolvedRecipe(@Nonnull GTRecipe recipe, int duration, long voltage, long amperage,
    boolean amperageOverclock, boolean unlimitedEnergy, int maxParallel, double durationMultiplier, double euModifier,
    double euModifierNotLimitingParallel, @Nonnull ProcessingSpec.OverclockRule overclock,
    @Nonnull OptionalInt maxOverclocks, int maxTierSkips, @Nullable Heat heat, long maxEuPerTick,
    @Nonnull CheckRecipeResult check, @Nonnull CheckRecipeResult checkToStart, @Nonnull BigInteger startupEu,
    @Nonnull BigInteger euPerRun, @Nonnull BigInteger euGeneratedPerRun, double successChance, double outputYield) {

    public record Heat(int machineHeat, int recipeHeat, boolean overclocking, boolean discounting) {}

    @Nonnull
    public ResolvedRecipe withMaxParallel(int maxParallel) {
        return new ResolvedRecipe(
            recipe,
            duration,
            voltage,
            amperage,
            amperageOverclock,
            unlimitedEnergy,
            maxParallel,
            durationMultiplier,
            euModifier,
            euModifierNotLimitingParallel,
            overclock,
            maxOverclocks,
            maxTierSkips,
            heat,
            maxEuPerTick,
            check,
            checkToStart,
            startupEu,
            euPerRun,
            euGeneratedPerRun,
            successChance,
            outputYield);
    }

    /** The EU/t that parallels may use up to. */
    public long availableEuPerTick() {
        return unlimitedEnergy ? Long.MAX_VALUE : voltage * amperage;
    }

    /** A fresh calculator for this recipe, before {@link OverclockCalculator#calculate()}. */
    @Nonnull
    public OverclockCalculator toCalculator() {
        return toCalculator(recipe.mEUt, duration);
    }

    /** As {@link #toCalculator()}, for another recipe the machine runs with the same settings. */
    @Nonnull
    public OverclockCalculator toCalculator(long recipeEuPerTick, int recipeDuration) {
        double euDiscount = euModifier * euModifierNotLimitingParallel;
        if (!(overclock instanceof ProcessingSpec.OverclockRule.Ratio ratio)) {
            return OverclockCalculator.ofNoOverclock(recipeEuPerTick, recipeDuration)
                .setDurationModifier(durationMultiplier)
                .setEUtDiscount(euDiscount);
        }
        OverclockCalculator calculator = new OverclockCalculator().setRecipeEUt(recipeEuPerTick)
            .setAmperage(amperage)
            .setEUt(voltage)
            .setMaxTierSkips(maxTierSkips)
            .setDuration(recipeDuration)
            .setDurationModifier(durationMultiplier)
            .setEUtDiscount(euDiscount)
            .setAmperageOC(amperageOverclock)
            .setDurationDecreasePerOC(ratio.durationDivisor())
            .setEUtIncreasePerOC(ratio.euMultiplier());
        maxOverclocks.ifPresent(calculator::setMaxOverclocks);
        if (heat != null) {
            calculator.setMachineHeat(heat.machineHeat())
                .setRecipeHeat(heat.recipeHeat())
                .setHeatOC(heat.overclocking())
                .setHeatDiscount(heat.discounting());
        }
        if (unlimitedEnergy) calculator.setAmperage(1)
            .setEUt(Long.MAX_VALUE);
        return calculator;
    }

    /** The EU/t a run draws, given its calculated overclocks. */
    public long euPerTick(@Nonnull OverclockCalculator calculator) {
        return Math.min(maxEuPerTick, calculator.getConsumption());
    }

    /** With unlimited inputs and output space, as a planner runs it. */
    @Nonnull
    public ProcessingRun calculate() {
        return calculate(toCalculator());
    }

    /** As {@link #calculate()}, with a calculator the caller has changed. */
    @Nonnull
    public ProcessingRun calculate(@Nonnull OverclockCalculator calculator) {
        if (!check.wasSuccessful()) return ProcessingRun.failed(check);
        if (!checkToStart.wasSuccessful()) return ProcessingRun.failed(checkToStart);
        ParallelHelper helper = new ParallelHelper().setRecipe(recipe)
            .setAvailableEUt(availableEuPerTick())
            .setMaxParallel(maxParallel)
            .setEUtModifier(euModifier)
            .setCalculator(calculator)
            .setConsumption(false)
            .setMaxParallelCalculator((r, max, fluids, items) -> max)
            .setInputConsumer((r, amount, fluids, items) -> {})
            .build();
        if (!helper.getResult()
            .wasSuccessful()) return ProcessingRun.failed(helper.getResult());
        if (calculator.getConsumption() == Long.MAX_VALUE) {
            return ProcessingRun.failed(CheckRecipeResultRegistry.POWER_OVERFLOW);
        }
        double ticks = calculator.getDuration() * helper.getDurationMultiplierDouble();
        if (calculator.getDuration() == Integer.MAX_VALUE || ticks >= Integer.MAX_VALUE) {
            return ProcessingRun.failed(CheckRecipeResultRegistry.DURATION_OVERFLOW);
        }
        return new ProcessingRun(
            helper.getResult(),
            helper.getCurrentParallel(),
            calculator.getPerformedOverclocks(),
            (int) ticks,
            euPerTick(calculator),
            startupEu,
            euPerRun,
            euGeneratedPerRun,
            successChance,
            outputYield);
    }
}
