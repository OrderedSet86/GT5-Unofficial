package gregtech.api.logic;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.gtnewhorizons.modularui.api.drawable.UITexture;

import gregtech.api.recipe.RecipeMap;

/**
 * {@code MachineMode.of(RecipeMaps.polarizerRecipes).nameKey("...").icon(...)}
 *
 * @param icon    For the ModularUI 1 GUI
 * @param guiIcon For the ModularUI 2 GUI
 */
public record MachineMode(@Nonnull RecipeMap<?> recipeMap, @Nonnull String nameKey, @Nullable UITexture icon,
    @Nullable com.cleanroommc.modularui.drawable.UITexture guiIcon) {

    @Nonnull
    public static MachineMode of(@Nonnull RecipeMap<?> recipeMap) {
        return new MachineMode(recipeMap, "GT5U.MULTI_MACHINE_MODE.unknown", null, null);
    }

    @Nonnull
    public MachineMode nameKey(@Nonnull String nameKey) {
        return new MachineMode(recipeMap, nameKey, icon, guiIcon);
    }

    @Nonnull
    public MachineMode icon(@Nonnull UITexture icon) {
        return new MachineMode(recipeMap, nameKey, icon, guiIcon);
    }

    @Nonnull
    public MachineMode guiIcon(@Nonnull com.cleanroommc.modularui.drawable.UITexture guiIcon) {
        return new MachineMode(recipeMap, nameKey, icon, guiIcon);
    }

    @Nonnull
    public MachineMode icon(@Nonnull UITexture icon, @Nonnull com.cleanroommc.modularui.drawable.UITexture guiIcon) {
        return new MachineMode(recipeMap, nameKey, icon, guiIcon);
    }
}
