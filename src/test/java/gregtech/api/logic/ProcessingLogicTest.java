package gregtech.api.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import gregtech.api.util.GTRecipe;
import gregtech.api.util.OverclockCalculator;

class ProcessingLogicTest {

    @Test
    void resolveModifierSuppliersReadsEachSupplier() {
        ProcessingLogic logic = new ProcessingLogic().setMaxParallelSupplier(() -> 24)
            .setSpeedBonusSupplier(() -> 0.4)
            .setEuModifierSupplier(() -> 0.9);

        logic.resolveModifierSuppliers();

        assertEquals(24, logic.getResolvedMaxParallel());
        assertEquals(0.4, logic.getResolvedSpeedBonus());
        assertEquals(0.9, logic.getResolvedEuModifier());
    }

    @Test
    void inspectionCalculatorCarriesTheLogicsModifiers() {
        ProcessingLogic logic = new ProcessingLogic().setSpeedBonus(0.5)
            .setEuModifier(0.75)
            .enablePerfectOverclock()
            .setUnlimitedTierSkips();
        // GTRecipe's constructor needs the game loaded; these are the only fields the calculator reads.
        GTRecipe recipe = mock(GTRecipe.class);
        recipe.mDuration = 200;
        recipe.mEUt = 30;

        OverclockCalculator calculator = logic.createOverclockCalculatorForInspection(recipe);

        assertEquals(30, calculator.getRecipeEUt());
        assertEquals(200, calculator.getRecipeDuration());
        assertEquals(0.5, calculator.getDurationModifier());
        assertEquals(0.75, calculator.getEUtDiscount());
        assertEquals(4, calculator.getDurationDecreasePerOC());
        assertEquals(Integer.MAX_VALUE, calculator.getMaxTierSkips());
    }
}
