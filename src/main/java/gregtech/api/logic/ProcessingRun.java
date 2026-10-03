package gregtech.api.logic;

import java.math.BigInteger;

import javax.annotation.Nonnull;

import gregtech.api.recipe.check.CheckRecipeResult;

/**
 * One run of a recipe, as {@link ResolvedRecipe#calculate} works it out.
 *
 * @param result    Unsuccessful means every number is 0. {@code getDisplayString()} then says why, as the machine's GUI
 *                  would, such as the coil heat a recipe needs
 * @param parallel  After the energy limit
 * @param euPerTick For all parallels together
 */
public record ProcessingRun(@Nonnull CheckRecipeResult result, int parallel, int overclocks, int ticks, long euPerTick,
    @Nonnull RunEu eu, @Nonnull Output output) {

    /**
     * EU besides EU/t.
     *
     * @param startup   Taken once when the machine starts from idle
     * @param perRun    Taken when each run starts
     * @param generated Given when each run ends
     */
    public record RunEu(@Nonnull BigInteger startup, @Nonnull BigInteger perRun, @Nonnull BigInteger generated) {

        public static final RunEu NONE = new RunEu(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
    }

    /**
     * @param successChance Of each parallel, 0 to 1
     * @param yield         Multiplies the outputs of each parallel that succeeds
     */
    public record Output(double successChance, double yield) {

        public static final Output CERTAIN = new Output(1, 1);
        public static final Output NONE = new Output(0, 0);
    }

    @Nonnull
    public static ProcessingRun failed(@Nonnull CheckRecipeResult result) {
        return new ProcessingRun(result, 0, 0, 0, 0, RunEu.NONE, Output.NONE);
    }
}
