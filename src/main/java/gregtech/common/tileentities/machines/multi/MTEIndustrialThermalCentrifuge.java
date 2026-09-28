package gregtech.common.tileentities.machines.multi;

import static com.gtnewhorizon.structurelib.structure.StructureUtility.ofChain;
import static com.gtnewhorizon.structurelib.structure.StructureUtility.onElementPass;
import static gregtech.api.enums.HatchElement.Energy;
import static gregtech.api.enums.HatchElement.InputBus;
import static gregtech.api.enums.HatchElement.Maintenance;
import static gregtech.api.enums.HatchElement.Muffler;
import static gregtech.api.enums.HatchElement.OutputBus;
import static gregtech.api.util.GTStructureUtility.buildHatchAdder;
import static gregtech.api.util.GTStructureUtility.chainAllGlasses;
import static gregtech.api.util.GTStructureUtility.ofFrame;

import java.util.List;

import javax.annotation.Nonnull;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.casing.Casings;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.enums.Materials;
import gregtech.api.enums.SoundResource;
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
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.tooltip.TooltipTier;
import gregtech.common.misc.GTStructureChannels;
import gregtech.common.pollution.PollutionConfig;
import gtPlusPlus.xmod.gregtech.common.blocks.textures.TexturesGtBlock;

public class MTEIndustrialThermalCentrifuge extends MTEExtendedPowerMultiBlockBase<MTEIndustrialThermalCentrifuge>
    implements ISurvivalConstructable, ICasingTextureProvider {

    private int casingAmount;
    private static final String STRUCTURE_PIECE_MAIN = "main";
    private static final int OFFSET_X = 2;
    private static final int OFFSET_Y = 7;
    private static final int OFFSET_Z = 0;

    private HeatingCoilLevel coilLevel = null;
    private Byte solenoidLevel = null;

    private static final ProcessingSpec SPEC = ProcessingSpec.builder()
        .parallelPerTier(8, TooltipTier.VOLTAGE)
        .parallelPerTier(2, TooltipTier.SOLENOID)
        .speedPerTierBeyondFirst(2.5, 0.05, TooltipTier.COIL)
        .euModifierPerTierBeyondFirst(0.8, 0.95, TooltipTier.COIL)
        .build();
    private static final StructureParameter.Of<MTEIndustrialThermalCentrifuge, HeatingCoilLevel> COIL = StructureParameter
        .coil(MTEIndustrialThermalCentrifuge::getCoilLevel, MTEIndustrialThermalCentrifuge::setCoilLevel);
    private static final StructureParameter.Of<MTEIndustrialThermalCentrifuge, Byte> SOLENOID = StructureParameter
        .solenoid(MTEIndustrialThermalCentrifuge::getSolenoidLevel, MTEIndustrialThermalCentrifuge::setSolenoidLevel);
    private static IStructureDefinition<MTEIndustrialThermalCentrifuge> STRUCTURE_DEFINITION = null;

    public MTEIndustrialThermalCentrifuge(final int aID, final String aName, final String aNameRegional) {
        super(aID, aName, aNameRegional);
    }

    public MTEIndustrialThermalCentrifuge(final String aName) {
        super(aName);
    }

    @Override
    public IMetaTileEntity newMetaEntity(final IGregTechTileEntity aTileEntity) {
        return new MTEIndustrialThermalCentrifuge(this.mName);
    }

    @Override
    protected MultiblockTooltipBuilder createTooltip() {
        MultiblockTooltipBuilder tt = new MultiblockTooltipBuilder();
        tt.addMachineType("Thermal Centrifuge, LTR")
            .addProcessingSpecInfo(SPEC)
            .addPollutionAmount(getPollutionPerSecond(null))
            .beginStructureBlock(5, 8, 6, false)
            .addController("Front bottom center")
            .addCasing("85-92", "Thermal Processing Casing", false)
            .addCasing("20", "Red Steel Frame Box", false)
            .addCasing("16", "Heating Coil", true)
            .addCasing("6", "Solenoid Superconducting Coil", true)
            .addCasing("6", "Any Tiered Glass", false)
            .addCasing("4", "Heat Proof Machine Casing", false)
            .addEnergyHatch("1+", "Any thermal casing", 1)
            .addMaintenanceHatch("1", "Any thermal casing", 1)
            .addMufflerHatch("1", "Any thermal casing", 1)
            .addInputBus("1+", "Any thermal casing", 1)
            .addOutputBus("1+", "Any thermal casing", 1)
            .addStructureInfo("")
            .addSubChannel(GTStructureChannels.HEATING_COIL)
            .addSubChannel(GTStructureChannels.SOLENOID)
            .addSubChannel(GTStructureChannels.BOROGLASS)
            .addStructureAuthors(EnumChatFormatting.GOLD + "Oasis_Cactus")
            .toolTipFinisher();
        return tt;
    }

    @Override
    public IStructureDefinition<MTEIndustrialThermalCentrifuge> getStructureDefinition() {
        if (STRUCTURE_DEFINITION == null) {
            STRUCTURE_DEFINITION = StructureDefinition.<MTEIndustrialThermalCentrifuge>builder()
                .addShape(
                    STRUCTURE_PIECE_MAIN,
                    (new String[][] { { " FFF ", " FFF ", "     ", "     ", "     ", "     ", " FFF ", " F~F " },
                        { "FFFFF", "FBDBF", "  D  ", "  E  ", "  E  ", "F D F", "FBDBF", "FFFFF" },
                        { "FFFFF", "FDCDF", "FDCDF", " ECE ", "FECEF", "FDCDF", "FDCDF", "FFFFF" },
                        { "FFFFF", "F D F", "F D F", "F E F", "F E F", "F D F", "F D F", "FFFFF" },
                        { "FFFFF", "EFAFE", "EFAFE", "EFAFE", "EFAFE", "EFAFE", "EFAFE", "FFFFF" },
                        { " FFF ", "     ", "     ", "     ", "     ", "     ", "     ", " FFF " } }))
                .addElement(
                    'F',
                    ofChain(
                        buildHatchAdder(MTEIndustrialThermalCentrifuge.class)
                            .atLeast(InputBus, OutputBus, Maintenance, Energy, Muffler)
                            .casingIndex(Casings.ThermalProcessingCasing.textureId)
                            .hint(1)
                            .build(),
                        onElementPass(x -> ++x.casingAmount, Casings.ThermalProcessingCasing.asElement())))
                .addElement('A', chainAllGlasses())
                .addElement('B', Casings.HeatProofMachineCasing.asElement())
                .addElement('C', SOLENOID)
                .addElement('D', COIL)
                .addElement('E', ofFrame(Materials.RedSteel))
                .build();
        }
        return STRUCTURE_DEFINITION;
    }

    @Override
    public ITexture[] getTexture(IGregTechTileEntity aBaseMetaTileEntity, ForgeDirection side, ForgeDirection aFacing,
        int colorIndex, boolean aActive, boolean redstoneLevel) {
        return Textures.BlockIcons.createTextureWithCasing(
            this,
            side,
            aFacing,
            aActive,
            TexturesGtBlock.oMCDIndustrialThermalCentrifuge,
            TexturesGtBlock.oMCDIndustrialThermalCentrifugeGlow,
            TexturesGtBlock.oMCDIndustrialThermalCentrifugeActive,
            TexturesGtBlock.oMCDIndustrialThermalCentrifugeActiveGlow);
    }

    @Override
    public ITexture getCasingTexture() {
        return Casings.ThermalProcessingCasing.getCasingTexture();
    }

    @Override
    public void construct(ItemStack stackSize, boolean hintsOnly) {
        buildPiece(STRUCTURE_PIECE_MAIN, stackSize, hintsOnly, OFFSET_X, OFFSET_Y, OFFSET_Z);
    }

    @Override
    public int survivalConstruct(ItemStack stackSize, int elementBudget, ISurvivalBuildEnvironment env) {
        if (mMachine) return -1;
        return survivalBuildPiece(
            STRUCTURE_PIECE_MAIN,
            stackSize,
            OFFSET_X,
            OFFSET_Y,
            OFFSET_Z,
            elementBudget,
            env,
            false,
            true);
    }

    @Override
    public void checkMachine(IGregTechTileEntity aBaseMetaTileEntity, ItemStack aStack, List<StructureError> errors) {
        casingAmount = 0;
        coilLevel = null;
        solenoidLevel = null;
        if (!checkPiece(STRUCTURE_PIECE_MAIN, OFFSET_X, OFFSET_Y, OFFSET_Z, errors)) return;
        checkCasingMin(errors, casingAmount, 85);
        checkHasEnergyHatch(errors);
        checkHasMaintenanceHatch(errors);
        checkHasMufflerHatch(errors);
        checkHasInputBus(errors);
        checkHasOutputBus(errors);
    }

    @Override
    public RecipeMap<?> getRecipeMap() {
        return RecipeMaps.thermalCentrifugeRecipes;
    }

    @Override
    protected ProcessingLogic createProcessingLogic() {
        return new ProcessingLogic();
    }

    @Override
    public ProcessingSpec getProcessingSpec() {
        return SPEC;
    }

    @Override
    public int getPollutionPerSecond(final ItemStack aStack) {
        return PollutionConfig.pollutionPerSecondMultiIndustrialThermalCentrifuge;
    }

    @SideOnly(Side.CLIENT)
    @Override
    protected SoundResource getActivitySoundLoop() {
        return SoundResource.GT_MACHINES_THERMAL_CENTRIFUGE_LOOP;
    }

    private HeatingCoilLevel getCoilLevel() {
        return coilLevel;
    }

    private void setCoilLevel(HeatingCoilLevel level) {
        coilLevel = level;
    }

    private Byte getSolenoidLevel() {
        return solenoidLevel;
    }

    private void setSolenoidLevel(byte level) {
        solenoidLevel = level;
    }

    @Override
    @Nonnull
    public List<StructureParameter> getStructureParametersForInspection() {
        return List.of(COIL.of(this), SOLENOID.of(this));
    }

    @Override
    public boolean supportsInputSeparation() {
        return true;
    }

    @Override
    public boolean supportsVoidProtection() {
        return true;
    }

    @Override
    public boolean supportsBatchMode() {
        return true;
    }
}
