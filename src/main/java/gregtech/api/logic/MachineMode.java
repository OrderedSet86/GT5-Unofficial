package gregtech.api.logic;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.cleanroommc.modularui.drawable.UITexture;

import gregtech.api.recipe.RecipeMap;

/**
 * One mode of a multiblock, as its mode button shows it.
 *
 * @param nameKey The translation key of the mode's name
 * @param guiIcon The mode button's icon
 */
public record MachineMode(@Nonnull RecipeMap<?> recipeMap, @Nonnull String nameKey, @Nullable UITexture guiIcon) {}
