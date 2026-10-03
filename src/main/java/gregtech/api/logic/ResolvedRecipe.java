package gregtech.api.logic;

import java.util.OptionalInt;
import java.util.function.DoubleSupplier;

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
public record ResolvedRecipe(@Nonnull GTRecipe recipe, int duration, @Nonnull ProcessingSpec.Power power,
    int maxParallel, double durationMultiplier, double euModifier, double euModifierNotLimitingParallel,
    @Nonnull Overclock overclock, @Nonnull CheckRecipeResult check, @Nonnull CheckRecipeResult checkToStart,
    @Nonnull ProcessingRun.RunEu eu, @Nonnull ProcessingRun.Output output) {

    /** @param heat Null where heat does not change the overclocks */
    public record Overclock(@Nonnull ProcessingSpec.OverclockRule rule, @Nonnull OptionalInt maxOverclocks,
        int maxTierSkips, @Nullable Heat heat) {}

    public record Heat(int machineHeat, int recipeHeat, boolean overclocking, boolean discounting) {}

    @Nonnull
    public ResolvedRecipe withMaxParallel(int maxParallel) {
        return new ResolvedRecipe(
            recipe,
            duration,
            power,
            maxParallel,
            durationMultiplier,
            euModifier,
            euModifierNotLimitingParallel,
            overclock,
            check,
            checkToStart,
            eu,
            output);
    }

    /** At most {@code limit} parallels, and at least 1, as the power panel or a planner's cap allows. */
    @Nonnull
    public ResolvedRecipe capParallel(int limit) {
        return limit >= maxParallel ? this : withMaxParallel(Math.max(1, limit));
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
        if (!(overclock.rule() instanceof ProcessingSpec.OverclockRule.Ratio ratio)) {
            return OverclockCalculator.ofNoOverclock(recipeEuPerTick, recipeDuration)
                .setDurationModifier(durationMultiplier)
                .setEUtDiscount(euDiscount);
        }
        OverclockCalculator calculator = new OverclockCalculator().setRecipeEUt(recipeEuPerTick)
            .setAmperage(power.amperage())
            .setEUt(power.voltage())
            .setMaxTierSkips(overclock.maxTierSkips())
            .setDuration(recipeDuration)
            .setDurationModifier(durationMultiplier)
            .setEUtDiscount(euDiscount)
            .setAmperageOC(power.amperageOverclock())
            .setDurationDecreasePerOC(ratio.durationDivisor())
            .setEUtIncreasePerOC(ratio.euMultiplier());
        overclock.maxOverclocks()
            .ifPresent(calculator::setMaxOverclocks);
        Heat heat = overclock.heat();
        if (heat != null) {
            calculator.setMachineHeat(heat.machineHeat())
                .setRecipeHeat(heat.recipeHeat())
                .setHeatOC(heat.overclocking())
                .setHeatDiscount(heat.discounting());
        }
        if (power.unlimited()) calculator.setAmperage(1)
            .setEUt(Long.MAX_VALUE);
        return calculator;
    }

    /** A helper with the energy, parallel and EU/t numbers set. The machine adds its inputs and void protection. */
    @Nonnull
    public ParallelHelper parallelHelper(@Nonnull GTRecipe recipe) {
        return new ParallelHelper().setRecipe(recipe)
            .setAvailableEUt(power.availableEuPerTick())
            .setMaxParallel(maxParallel)
            .setEUtModifier(euModifier);
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
        ParallelHelper helper = forPlanning(parallelHelper(recipe)).setCalculator(calculator)
            .build();
        return toRun(helper, calculator, () -> ticks(helper, calculator));
    }

    /**
     * Unlimited inputs and output space: no void protection, recipe lock, batch or input consumption. Apply it after
     * the machine has set up the helper.
     */
    @Nonnull
    public static ParallelHelper forPlanning(@Nonnull ParallelHelper helper) {
        return helper.setMachine(null, false, false)
            .setRecipeLocked(null, false)
            .enableBatchMode(1)
            .setConsumption(false)
            .setOutputCalculation(false)
            .setMaxParallelCalculator((recipe, max, fluids, items) -> max)
            .setInputConsumer((recipe, amount, fluids, items) -> {});
    }

    /** The recipe's time after its overclocks and batch. */
    public static double ticks(@Nonnull ParallelHelper helper, @Nonnull OverclockCalculator calculator) {
        return calculator.getDuration() * helper.getDurationMultiplierDouble();
    }

    /**
     * The run, given the built helper and its calculated overclocks: the helper's failure or an overflow if any.
     *
     * @param ticks Read only once nothing has overflowed
     */
    @Nonnull
    public ProcessingRun toRun(@Nonnull ParallelHelper helper, @Nonnull OverclockCalculator calculator,
        @Nonnull DoubleSupplier ticks) {
        if (!helper.getResult()
            .wasSuccessful()) return ProcessingRun.failed(helper.getResult());
        if (calculator.getConsumption() == Long.MAX_VALUE) {
            return ProcessingRun.failed(CheckRecipeResultRegistry.POWER_OVERFLOW);
        }
        if (calculator.getDuration() == Integer.MAX_VALUE) {
            return ProcessingRun.failed(CheckRecipeResultRegistry.DURATION_OVERFLOW);
        }
        double runTicks = ticks.getAsDouble();
        if (runTicks >= Integer.MAX_VALUE) return ProcessingRun.failed(CheckRecipeResultRegistry.DURATION_OVERFLOW);
        return new ProcessingRun(
            helper.getResult(),
            helper.getCurrentParallel(),
            calculator.getPerformedOverclocks(),
            (int) runTicks,
            Math.min(power.maxEuPerTick(), calculator.getConsumption()),
            eu,
            output);
    }
}
