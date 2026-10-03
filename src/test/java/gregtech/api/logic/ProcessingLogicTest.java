package gregtech.api.logic;

import static gregtech.api.logic.TestRecipes.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import gregtech.api.util.GTRecipe;
import gregtech.api.util.OverclockCalculator;

class ProcessingLogicTest {

    @Test
    void inspectionCalculatorCarriesTheLogicsModifiers() {
        ProcessingLogic logic = new ProcessingLogic().setSpeedBonus(0.5)
            .setEuModifier(0.75)
            .enablePerfectOverclock()
            .setUnlimitedTierSkips();
        GTRecipe recipe = recipe(30, 200, 0);

        OverclockCalculator calculator = logic.createOverclockCalculatorForInspection(recipe);

        assertEquals(30, calculator.getRecipeEUt());
        assertEquals(200, calculator.getRecipeDuration());
        assertEquals(0.5, calculator.getDurationModifier());
        assertEquals(0.75, calculator.getEUtDiscount());
        assertEquals(4, calculator.getDurationDecreasePerOC());
        assertEquals(Integer.MAX_VALUE, calculator.getMaxTierSkips());
    }
}
