package gregtech.api.logic;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.cleanroommc.modularui.drawable.UITexture;

import gregtech.api.recipe.RecipeMap;

/**
 * One mode of a multiblock: the mode's recipe map, name and button icon.
 *
 * @param nameKey A lang key
 */
public record MachineMode(@Nonnull RecipeMap<?> recipeMap, @Nonnull String nameKey, @Nullable UITexture guiIcon) {}
