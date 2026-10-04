package gregtech.api.logic;

import java.math.BigInteger;

import javax.annotation.Nonnull;

import gregtech.api.recipe.check.CheckRecipeResult;

/**
 * One run of a recipe, as {@link ResolvedRecipe#calculate} works it out.
 *
 * @param result    Unsuccessful means every number is 0. {@code getDisplayString()} then returns the reason printed
 *                  in the machine GUI, such as the coil heat the recipe requires
 * @param parallel  After the energy limit
 * @param euPerTick For all parallels together
 */
public record ProcessingRun(@Nonnull CheckRecipeResult result, int parallel, int overclocks, int ticks, long euPerTick,
    @Nonnull RunEu eu) {

    /**
     * EU besides EU/t.
     *
     * @param startup Taken once when the machine starts from idle
     */
    public record RunEu(@Nonnull BigInteger startup) {

        public static final RunEu NONE = new RunEu(BigInteger.ZERO);
    }

    @Nonnull
    public static ProcessingRun failed(@Nonnull CheckRecipeResult result) {
        return new ProcessingRun(result, 0, 0, 0, 0, RunEu.NONE);
    }
}
