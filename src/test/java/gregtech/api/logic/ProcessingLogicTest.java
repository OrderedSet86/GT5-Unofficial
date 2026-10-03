package gregtech.api.logic;

import static gregtech.api.logic.TestRecipes.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import gregtech.api.enums.VoltageIndex;
import gregtech.api.util.GTRecipe;

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

    /** As the scanner shows it. */
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
}
