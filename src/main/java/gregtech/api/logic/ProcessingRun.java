package gregtech.api.logic;

import java.math.BigInteger;

import javax.annotation.Nonnull;

import gregtech.api.recipe.check.CheckRecipeResult;

/**
 * One run of a recipe, as {@link ResolvedRecipe#calculate} works it out.
 *
 * @param result            Unsuccessful means every number is 0. {@code getDisplayString()} then says why, as the
 *                          machine's GUI would, such as the coil heat a recipe needs
 * @param parallel          After the energy limit
 * @param euPerTick         For all parallels together
 * @param startupEu         Taken once when the machine starts from idle
 * @param euPerRun          Taken when each run starts, besides {@code euPerTick}
 * @param euGeneratedPerRun Given when each run ends
 * @param successChance     Of each parallel, 0 to 1
 * @param outputYield       Multiplies the outputs of each parallel that succeeds
 */
public record ProcessingRun(@Nonnull CheckRecipeResult result, int parallel, int overclocks, int ticks, long euPerTick,
    @Nonnull BigInteger startupEu, @Nonnull BigInteger euPerRun, @Nonnull BigInteger euGeneratedPerRun,
    double successChance, double outputYield) {

    @Nonnull
    public static ProcessingRun failed(@Nonnull CheckRecipeResult result) {
        return new ProcessingRun(result, 0, 0, 0, 0, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, 0, 0);
    }
}
