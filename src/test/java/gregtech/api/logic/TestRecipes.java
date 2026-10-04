package gregtech.api.logic;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import gregtech.api.util.GTRecipe;

final class TestRecipes {

    private TestRecipes() {}

    /** A mock, since GTRecipe's constructor needs the game loaded. */
    static GTRecipe recipe(int eut, int duration, int heat) {
        GTRecipe recipe = mock(GTRecipe.class);
        recipe.mEUt = eut;
        recipe.mDuration = duration;
        recipe.mSpecialValue = heat;
        when(recipe.copy()).thenAnswer(invocation -> recipe(recipe.mEUt, recipe.mDuration, recipe.mSpecialValue));
        return recipe;
    }
}
