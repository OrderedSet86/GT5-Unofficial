package gregtech.api.logic;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.gtnewhorizons.modularui.api.drawable.UITexture;

import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeMap;

/**
 * One mode of a multiblock that switches between recipe maps, as {@link MTEMultiBlockBase#getMachineModes()} lists
 * them: {@code MachineMode.of(RecipeMaps.polarizerRecipes).nameKey("...").icon(...)}.
 *
 * @param recipeMap The recipes the machine runs in this mode
 * @param nameKey   The translation key of the mode's name
 * @param icon      The mode button's icon in the ModularUI 1 GUI
 * @param guiIcon   The mode button's icon in the ModularUI 2 GUI
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

    /** The mode button's icon in the ModularUI 1 GUI only, for a machine without a ModularUI 2 GUI. */
    @Nonnull
    public MachineMode icon(@Nonnull UITexture icon) {
        return new MachineMode(recipeMap, nameKey, icon, guiIcon);
    }

    /** The mode button's icon in the ModularUI 2 GUI only, for a machine without a ModularUI 1 GUI. */
    @Nonnull
    public MachineMode guiIcon(@Nonnull com.cleanroommc.modularui.drawable.UITexture guiIcon) {
        return new MachineMode(recipeMap, nameKey, icon, guiIcon);
    }

    /** The mode button's icon in both GUIs. */
    @Nonnull
    public MachineMode icon(@Nonnull UITexture icon, @Nonnull com.cleanroommc.modularui.drawable.UITexture guiIcon) {
        return new MachineMode(recipeMap, nameKey, icon, guiIcon);
    }
}
