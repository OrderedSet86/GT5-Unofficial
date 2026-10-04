package gregtech.api.logic;

import static gregtech.api.logic.TestRecipes.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import gregtech.api.enums.VoltageIndex;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.OverclockCalculator;

class ProcessingLogicTest {

    private static final ProcessingSpec SPEC = ProcessingSpec.builder()
        .parallelPerTier(16, ModifierKind.VOLTAGE)
        .speed(2)
        .euModifier(0.75)
        .build();
    private static final ProcessingInputs LUV = ProcessingInputs.builder()
        .energyHatches(VoltageIndex.LuV, 2)
        .build();

    @Test
    void aMachineRunsARecipeAsItsSpecSays() {
        GTRecipe recipe = recipe(30, 200, 0);

        ProcessingRun machine = new ProcessingLogic().setSpec(SPEC, LUV)
            .inspect(recipe);

        assertEquals(SPEC.calculate(recipe, LUV), machine);
    }

    /** As the scanner prints it. */
    @Test
    void theHighestRecipeVoltageFollowsTheSpec() {
        ProcessingLogic logic = new ProcessingLogic().setAvailableVoltage(32)
            .setSpec(
                ProcessingSpec.builder()
                    .unlimitedTierSkips()
                    .build(),
                LUV);

        assertEquals(Long.MAX_VALUE, logic.getMaxAllowedRecipeEUt());
    }

    @Test
    void thePowerPanelCapsTheSpecsParallels() {
        ProcessingRun capped = new ProcessingLogic().setSpec(SPEC, LUV)
            .setParallelLimit(5)
            .inspect(recipe(30, 200, 0));

        assertEquals(5, capped.parallel());
    }

    /** A machine without a spec runs on its setters, through the same calculator a spec gives. */
    @Test
    void theSettersReachTheCalculator() {
        OverclockCalculator calculator = new ProcessingLogic().setAvailableVoltage(512)
            .setAvailableAmperage(4)
            .setSpeedBonus(0.5)
            .setEuModifier(0.75)
            .setOverclock(1.5, 3)
            .setAmperageOC(false)
            .setUnlimitedTierSkips()
            .createOverclockCalculator(recipe(30, 200, 0));

        assertEquals(4, calculator.getMachineAmperage());
        assertEquals(0.5, calculator.getDurationModifier());
        assertEquals(0.75, calculator.getEUtDiscount());
        assertEquals(1.5, calculator.getDurationDecreasePerOC());
        assertEquals(3, calculator.getEUtIncreasePerOC());
        assertEquals(Integer.MAX_VALUE, calculator.getMaxTierSkips());
        assertEquals(200, calculator.getRecipeDuration());
    }

    /** Hooks such as validateRecipe may change the setters during a check, and the later hooks read the change. */
    @Test
    void theHooksReadTheSettersAsTheyAreNow() {
        ProcessingLogic logic = new ProcessingLogic().setEuModifier(0.5);
        GTRecipe recipe = recipe(30, 200, 0);
        logic.createOverclockCalculator(recipe);

        logic.setEuModifier(0.25);

        assertEquals(
            0.25,
            logic.createOverclockCalculator(recipe)
                .getEUtDiscount());
    }
}
