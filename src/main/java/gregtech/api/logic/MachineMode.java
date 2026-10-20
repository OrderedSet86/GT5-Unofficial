package gregtech.api.logic;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.cleanroommc.modularui.drawable.UITexture;

import gregtech.api.recipe.RecipeMap;

/**
 * One mode of a multiblock. {@code recipeMap} is null for a mode without a recipe map. The Deep Earth Heating Pump
 * computes the output of each mode in code, and the Integrated Ore Factory chains the recipe maps of several machines
 * in each mode.
 */
public record MachineMode(@Nullable RecipeMap<?> recipeMap, @Nonnull String nameKey, @Nullable UITexture guiIcon) {}
