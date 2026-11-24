package gregtech.api.logic;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.cleanroommc.modularui.drawable.UITexture;

import gregtech.api.recipe.RecipeMap;

/**
 * {@link gregtech.api.metatileentity.implementations.MTEMultiBlockBase#getMachineModes} returns a multiblock's modes as
 * {@code MachineMode} records. {@code recipeMap} is null for a mode without a recipe map.
 */
public record MachineMode(@Nullable RecipeMap<?> recipeMap, @Nonnull String nameKey, @Nullable UITexture guiIcon) {}
