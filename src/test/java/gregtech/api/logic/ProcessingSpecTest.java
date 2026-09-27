package gregtech.api.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import gregtech.api.util.GTRecipe;
import gregtech.api.util.OverclockCalculator;
import gregtech.api.util.tooltip.TooltipTier;

class ProcessingSpecTest {

    private static ProcessingSpec.Inputs inputs(int voltageTier, int coilTier) {
        return new ProcessingSpec.Inputs(voltageTier, 0, kind -> {
            if (kind != TooltipTier.COIL) throw new IllegalArgumentException(kind.name());
            return coilTier;
        });
    }

    private static GTRecipe recipe(int eut, int duration, int heat) {
        // GTRecipe's constructor needs the game loaded; these are the only fields the calculator reads.
        GTRecipe recipe = mock(GTRecipe.class);
        recipe.mEUt = eut;
        recipe.mDuration = duration;
        recipe.mSpecialValue = heat;
        return recipe;
    }

    @Test
    void parallelTermsAddUpAndNeverFallBelowOne() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallelPerTier(4, TooltipTier.VOLTAGE)
            .parallelPerTier(2, TooltipTier.COIL)
            .build();

        assertEquals(4 * 5 + 2 * 3, spec.getMaxParallel(inputs(5, 3)));
        assertEquals(1, spec.getMaxParallel(inputs(0, 0)));
        assertEquals(1, ProcessingSpec.STANDARD.getMaxParallel(inputs(5, 3)));
    }

    @Test
    void speedIsTheReciprocalOfTheTooltipSpeed() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .speed(2.5F)
            .build();

        assertEquals(1F / 2.5F, spec.getSpeedBonus(inputs(1, 0)));
    }

    @Test
    void applySpecSetsOnlyWhatTheSpecDeclares() {
        ProcessingLogic logic = new ProcessingLogic().setSpeedBonus(0.5)
            .setMaxParallel(7)
            .setOverclock(3, 4)
            .applySpec(
                ProcessingSpec.builder()
                    .euModifier(0.8F)
                    .unlimitedTierSkips()
                    .build(),
                () -> inputs(5, 3),
                () -> 99);

        OverclockCalculator calculator = logic.createOverclockCalculatorForInspection(recipe(30, 200, 0));

        assertEquals(7, logic.getResolvedMaxParallel());
        assertEquals(0.5, calculator.getDurationModifier());
        assertEquals(0.8F, calculator.getEUtDiscount());
        assertEquals(3, calculator.getDurationDecreasePerOC());
        assertEquals(Integer.MAX_VALUE, calculator.getMaxTierSkips());
        assertFalse(calculator.isHeatOC());
    }

    @Test
    void applySpecCarriesHeatIntoTheCalculator() {
        ProcessingLogic logic = new ProcessingLogic().applySpec(
            ProcessingSpec.builder()
                .parallel(4)
                .heatOverclock(in -> 1000 * in.tier(TooltipTier.COIL))
                .heatDiscount()
                .build(),
            () -> inputs(5, 3),
            () -> 3);

        OverclockCalculator calculator = logic.createOverclockCalculatorForInspection(recipe(30, 200, 1800));

        assertEquals(3, logic.getResolvedMaxParallel());
        assertEquals(3000, calculator.getMachineHeat());
        assertEquals(1800, calculator.getRecipeHeat());
        assertTrue(calculator.isHeatOC());
        assertTrue(calculator.isHeatDiscount());
    }

    @Test
    void aFixedRecipeHeatReplacesTheRecipes() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .heatOverclock(in -> 3600)
            .recipeHeat(0)
            .build();

        assertEquals(0, spec.getRecipeHeat(recipe(30, 200, 1800)));
    }

    @Test
    void bestCaseNumbersAreLeftToTheMachine() {
        ProcessingLogic logic = new ProcessingLogic().setSpeedBonus(0.5)
            .setMaxParallel(7)
            .applySpec(
                ProcessingSpec.builder()
                    .parallel(64)
                    .speed(4F)
                    .bestCase(ProcessingSpec.Quantity.PARALLEL, ProcessingSpec.Quantity.SPEED_BONUS)
                    .build(),
                () -> inputs(5, 3),
                () -> 64);

        OverclockCalculator calculator = logic.createOverclockCalculatorForInspection(recipe(30, 200, 0));

        assertEquals(7, logic.getResolvedMaxParallel());
        assertEquals(0.5, calculator.getDurationModifier());
    }

    /** As the steam multiblocks ran before they declared a spec. */
    @Test
    void noOverclockRunsAtTheRecipesOwnVoltageWithItsCost() {
        ProcessingLogic logic = new ProcessingLogic().setAvailableVoltage(32)
            .applySpec(
                ProcessingSpec.builder()
                    .speedBonus(in -> 0.8)
                    .energyCost(in -> 2.5)
                    .noOverclock()
                    .build(),
                () -> inputs(1, 0),
                () -> 8);
        GTRecipe recipe = recipe(16, 200, 0);

        OverclockCalculator planned = logic.createOverclockCalculatorForInspection(recipe)
            .setParallel(8)
            .calculate();
        OverclockCalculator expected = OverclockCalculator.ofNoOverclock(recipe)
            .setEUtDiscount(2.5)
            .setDurationModifier(0.8)
            .setParallel(8)
            .calculate();

        assertTrue(planned.isNoOverclock());
        assertEquals(expected.getMaxAllowedRecipeEUt(), planned.getMaxAllowedRecipeEUt());
        assertEquals(expected.getDuration(), planned.getDuration());
        assertEquals(expected.getConsumption(), planned.getConsumption());
    }
}
