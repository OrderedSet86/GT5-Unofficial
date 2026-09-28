package gregtech.common.tileentities.machines.multi;

import static com.gtnewhorizon.structurelib.structure.StructureUtility.*;
import static gregtech.api.enums.HatchElement.*;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_INDUSTRIAL_EXTRACTOR;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_INDUSTRIAL_EXTRACTOR_ACTIVE;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_INDUSTRIAL_EXTRACTOR_ACTIVE_GLOW;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_INDUSTRIAL_EXTRACTOR_GLOW;
import static gregtech.api.util.GTStructureUtility.buildHatchAdder;
import static gregtech.api.util.GTStructureUtility.chainAllGlasses;
import static gregtech.api.util.GTStructureUtility.chainItemPipeCasings;

import java.util.List;

import javax.annotation.Nonnull;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.ICasingTextureProvider;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.logic.ProcessingLogic;
import gregtech.api.logic.ProcessingSpec;
import gregtech.api.metatileentity.implementations.MTEExtendedPowerMultiBlockBase;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.structure.StructureParameter;
import gregtech.api.structure.error.StructureError;
import gregtech.api.util.GTUtility;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.tooltip.TooltipTier;
import gregtech.common.blocks.BlockCasings4;
import gregtech.common.misc.GTStructureChannels;

public class MTEIndustrialExtractor extends MTEExtendedPowerMultiBlockBase<MTEIndustrialExtractor>
    implements ISurvivalConstructable, ICasingTextureProvider {

    private static final String STRUCTURE_PIECE_MAIN = "main";
    private static final ProcessingSpec SPEC = ProcessingSpec.builder()
        .parallelPerTier(8, TooltipTier.ITEM_PIPE_CASING)
        .speed(3)
        .euModifier(0.85)
        .build();
    private static final StructureParameter.Of<MTEIndustrialExtractor, Integer> ITEM_PIPE = StructureParameter
        .itemPipeCasing(MTEIndustrialExtractor::getItemPipeTier, MTEIndustrialExtractor::setItemPipeTier);
    private static final IStructureDefinition<MTEIndustrialExtractor> STRUCTURE_DEFINITION = StructureDefinition
        .<MTEIndustrialExtractor>builder()
        .addShape(
            STRUCTURE_PIECE_MAIN,
            (new String[][] { { "CCCCC", "C   C", "C   C", "C   C", "CC~CC" },
                { "CCCCC", " BBB ", " AAA ", " BBB ", "CCCCC" }, { "CCCCC", " BBB ", " ABA ", " BBB ", "CCCCC" },
                { "CCCCC", " BBB ", " AAA ", " BBB ", "CCCCC" }, { "CCCCC", "C   C", "C   C", "C   C", "CCCCC" } }))
        .addElement(
            'C',
            buildHatchAdder(MTEIndustrialExtractor.class).atLeast(InputBus, OutputBus, Maintenance, Energy)
                .casingIndex(((BlockCasings4) GregTechAPI.sBlockCasings4).getTextureIndex(1))
                .hint(1)
                .buildAndChain(
                    onElementPass(MTEIndustrialExtractor::onCasingAdded, ofBlock(GregTechAPI.sBlockCasings4, 1))))
        .addElement('B', chainItemPipeCasings(-1, ITEM_PIPE))
        .addElement('A', chainAllGlasses())
        .build();

    private int itemPipeTier = -1;

    private void setItemPipeTier(int tier) {
        itemPipeTier = tier;
    }

    private int getItemPipeTier() {
        return itemPipeTier;
    }

    @Override
    @Nonnull
    public List<StructureParameter> getStructureParametersForInspection() {
        return List.of(ITEM_PIPE.of(this));
    }

    public MTEIndustrialExtractor(final int aID, final String aName, final String aNameRegional) {
        super(aID, aName, aNameRegional);
    }

    public MTEIndustrialExtractor(String aName) {
        super(aName);
    }

    @Override
    public IStructureDefinition<MTEIndustrialExtractor> getStructureDefinition() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTEIndustrialExtractor(this.mName);
    }

    @Override
    public ITexture[] getTexture(IGregTechTileEntity aBaseMetaTileEntity, ForgeDirection side, ForgeDirection aFacing,
        int colorIndex, boolean aActive, boolean redstoneLevel) {
        return Textures.BlockIcons.createTextureWithCasing(
            this,
            side,
            aFacing,
            aActive,
            OVERLAY_FRONT_INDUSTRIAL_EXTRACTOR,
            OVERLAY_FRONT_INDUSTRIAL_EXTRACTOR_GLOW,
            OVERLAY_FRONT_INDUSTRIAL_EXTRACTOR_ACTIVE,
            OVERLAY_FRONT_INDUSTRIAL_EXTRACTOR_ACTIVE_GLOW);
    }

    @Override
    public ITexture getCasingTexture() {
        return Textures.BlockIcons
            .getCasingTextureForId(GTUtility.getCasingTextureIndex(GregTechAPI.sBlockCasings4, 1));
    }

    @Override
    protected MultiblockTooltipBuilder createTooltip() {
        MultiblockTooltipBuilder tt = new MultiblockTooltipBuilder();
        tt.addMachineType("Extractor")
            .addProcessingSpecInfo(SPEC)
            .beginStructureBlock(5, 5, 5, false)
            .addController("Front bottom center")
            .addCasing("45-57", "Stainless Steel Machine Casing", false)
            .addCasing("19", "Item Pipe Casing", true)
            .addCasing("8", "Any Tiered Glass", false)
            .addEnergyHatch("1+", "Any machine casing", 1)
            .addMaintenanceHatch("1", "Any machine casing", 1)
            .addInputBus("1+", "Any machine casing", 1)
            .addOutputBus("1+", "Any machine casing", 1)
            .addStructureInfo("")
            .addSubChannel(GTStructureChannels.ITEM_PIPE_CASING)
            .addSubChannel(GTStructureChannels.BOROGLASS)
            .toolTipFinisher();
        return tt;
    }

    @Override
    public void construct(ItemStack stackSize, boolean hintsOnly) {
        buildPiece(STRUCTURE_PIECE_MAIN, stackSize, hintsOnly, 2, 4, 0);
    }

    @Override
    public int survivalConstruct(ItemStack stackSize, int elementBudget, ISurvivalBuildEnvironment env) {
        if (mMachine) return -1;
        return survivalBuildPiece(STRUCTURE_PIECE_MAIN, stackSize, 2, 4, 0, elementBudget, env, false, true);
    }

    private int mCasingAmount;

    private void onCasingAdded() {
        mCasingAmount++;
    }

    @Override
    public void checkMachine(IGregTechTileEntity aBaseMetaTileEntity, ItemStack aStack, List<StructureError> errors) {
        mCasingAmount = 0;
        itemPipeTier = -1;
        if (!checkPiece(STRUCTURE_PIECE_MAIN, 2, 4, 0, errors)) return;
        checkCasingMin(errors, mCasingAmount, 45);
        checkHasEnergyHatch(errors);
        checkHasMaintenanceHatch(errors);
        checkHasInputBus(errors);
        checkHasOutputBus(errors);
    }

    @Override
    protected ProcessingLogic createProcessingLogic() {
        return new ProcessingLogic().noRecipeCaching();
    }

    @Override
    public ProcessingSpec getProcessingSpec() {
        return SPEC;
    }

    @Override
    public RecipeMap<?> getRecipeMap() {
        return RecipeMaps.extractorRecipes;
    }

    @Override
    public int getRecipeCatalystPriority() {
        return -10;
    }

    @Override
    public boolean supportsVoidProtection() {
        return true;
    }

    @Override
    public boolean supportsBatchMode() {
        return true;
    }

    @Override
    public boolean supportsInputSeparation() {
        return true;
    }
}
