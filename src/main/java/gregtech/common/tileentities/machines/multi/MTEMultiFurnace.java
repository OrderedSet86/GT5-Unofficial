package gregtech.common.tileentities.machines.multi;

import static com.gtnewhorizon.gtnhlib.util.numberformatting.NumberFormatUtil.formatNumber;
import static com.gtnewhorizon.structurelib.structure.StructureUtility.transpose;
import static gregtech.api.enums.GTValues.VN;
import static gregtech.api.enums.GTValues.VP;
import static gregtech.api.enums.HatchElement.Energy;
import static gregtech.api.enums.HatchElement.InputBus;
import static gregtech.api.enums.HatchElement.Maintenance;
import static gregtech.api.enums.HatchElement.Muffler;
import static gregtech.api.enums.HatchElement.OutputBus;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_MULTI_SMELTER;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_MULTI_SMELTER_ACTIVE;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_MULTI_SMELTER_ACTIVE_GLOW;
import static gregtech.api.enums.Textures.BlockIcons.OVERLAY_FRONT_MULTI_SMELTER_GLOW;
import static gregtech.api.enums.Textures.BlockIcons.casingTexturePages;
import static gregtech.api.util.GTStructureUtility.buildHatchAdder;
import static gregtech.api.util.GTUtility.validMTEList;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import javax.annotation.Nonnull;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import org.jetbrains.annotations.NotNull;

import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;

import gregtech.GTMod;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.ICasingTextureProvider;
import gregtech.api.interfaces.tileentity.IGregTechDeviceInformation;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.logic.Modifier;
import gregtech.api.logic.ModifierKind;
import gregtech.api.logic.ProcessingSpec;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.structure.error.StructureError;
import gregtech.api.structure.error.StructureErrorRegistry;
import gregtech.api.util.GTModHandler;
import gregtech.api.util.GTUtility;
import gregtech.api.util.ItemEjectionHelper;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.OverclockCalculator;
import gregtech.common.misc.GTStructureChannels;

public class MTEMultiFurnace extends MTEAbstractMultiFurnace<MTEMultiFurnace>
    implements ISurvivalConstructable, ICasingTextureProvider {

    private int mLevel = 0;

    private static final ProcessingSpec.RecipeOverride RECIPE = ProcessingSpec.RecipeOverride.eut(4)
        .duration(128);
    private static final ProcessingSpec SPEC = ProcessingSpec.builder()
        .parallel(in -> 4 << (in.value(ModifierKind.COIL) + 1))
        .customTooltip(
            ProcessingSpec.Quantity.PARALLEL,
            tt -> tt.addStaticParallelInfo(4)
                .addDynamicMultiplicativeParallelInfo(2, ModifierKind.COIL))
        .recipeOverride(RECIPE)
        .power(in -> GTUtility.roundUpVoltage(in.totalVoltage()), in -> 1)
        .noAmperageOverclock()
        .maxEuPerTick(in -> VP[GTUtility.getTier(in.averageVoltage())])
        .noTooltip(ProcessingSpec.Quantity.POWER)
        .build();
    private static final Modifier.Of<MTEMultiFurnace, HeatingCoilLevel> COIL = Modifier
        .coil(MTEMultiFurnace::getCoilLevel, MTEMultiFurnace::setCoilLevel);
    private static final int CASING_INDEX = 11;
    private static final String STRUCTURE_PIECE_MAIN = "main";
    private static final IStructureDefinition<MTEMultiFurnace> STRUCTURE_DEFINITION = StructureDefinition
        .<MTEMultiFurnace>builder()
        .addShape(
            STRUCTURE_PIECE_MAIN,
            transpose(new String[][] { { "ccc", "cmc", "ccc" }, { "CCC", "C-C", "CCC" }, { "b~b", "bbb", "bbb" } }))
        .addElement(
            'c',
            buildHatchAdder(MTEMultiFurnace.class).atLeast(Maintenance)
                .casingIndex(CASING_INDEX)
                .hint(3)
                .buildAndChain(GregTechAPI.sBlockCasings1, CASING_INDEX))
        .addElement('m', Muffler.newAny(CASING_INDEX, 2))
        .addElement('C', COIL)
        .addElement(
            'b',
            buildHatchAdder(MTEMultiFurnace.class).atLeast(Maintenance, InputBus, OutputBus, Energy)
                .casingIndex(CASING_INDEX)
                .hint(1)
                .buildAndChain(GregTechAPI.sBlockCasings1, CASING_INDEX))
        .build();

    public MTEMultiFurnace(int aID, String aName, String aNameRegional) {
        super(aID, aName, aNameRegional);
    }

    public MTEMultiFurnace(String aName) {
        super(aName);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTEMultiFurnace(this.mName);
    }

    @Override
    protected MultiblockTooltipBuilder createTooltip() {
        MultiblockTooltipBuilder tt = new MultiblockTooltipBuilder();
        tt.addMachineType("Furnace")
            .addProcessingSpecInfo(SPEC)
            .addPollutionAmount(getPollutionPerSecond(null))
            .beginStructureBlock(3, 3, 3, true)
            .addController("Front bottom center")
            .addCasing("8-12", "Heat Proof Machine Casing", false)
            .addCasing("8", "Heating Coil", true)
            .addEnergyHatch("1", "Any bottom casing", 1)
            .addMaintenanceHatch("1", "Any bottom casing", 1)
            .addMufflerHatch("1", "Top center casing", 2)
            .addInputBus("1+", "Any bottom casing", 1)
            .addOutputBus("1+", "Any bottom casing", 1)
            .addAir("Interior of the structure")
            .addStructureInfo("")
            .addSubChannel(GTStructureChannels.HEATING_COIL)
            .toolTipFinisher();
        return tt;
    }

    @Override
    public ITexture[] getTexture(IGregTechTileEntity aBaseMetaTileEntity, ForgeDirection side, ForgeDirection aFacing,
        int colorIndex, boolean aActive, boolean redstoneLevel) {
        return Textures.BlockIcons.createTextureWithCasing(
            this,
            side,
            aFacing,
            aActive,
            OVERLAY_FRONT_MULTI_SMELTER,
            OVERLAY_FRONT_MULTI_SMELTER_GLOW,
            OVERLAY_FRONT_MULTI_SMELTER_ACTIVE,
            OVERLAY_FRONT_MULTI_SMELTER_ACTIVE_GLOW);
    }

    @Override
    public ITexture getCasingTexture() {
        return casingTexturePages[0][CASING_INDEX];
    }

    /*
     * NOTE: If you are wondering why your machine is not showing up in the NEIHandler for furnaces...
     * it is handled in the NEI fork's catalysts.csv . so that multiple mods can show up in the same handler.
     */
    @Override
    public RecipeMap<?> getRecipeMap() {
        return RecipeMaps.furnaceRecipes;
    }

    @Override
    public int getPollutionPerSecond(ItemStack aStack) {
        return GTMod.proxy.mPollutionMultiSmelterPerSecond;
    }

    // Not GPL
    @Override
    public boolean supportsPowerPanel() {
        return false;
    }

    @Override
    @NotNull
    public CheckRecipeResult checkProcessing() {
        List<ItemStack> tInput = getAllStoredInputs();
        ProcessingSpec.Inputs inputs = getCurrentProcessingSpecInputs();
        long availableEUt = SPEC.getPower(inputs)
            .voltage();
        if (availableEUt < RECIPE.eut()) {
            return CheckRecipeResultRegistry.insufficientPower(RECIPE.eut());
        }
        if (tInput.isEmpty()) {
            return CheckRecipeResultRegistry.NO_RECIPE;
        }
        int maxParallel = this.mLevel;
        int originalMaxParallel = this.mLevel;

        OverclockCalculator calculator = new OverclockCalculator().setEUt(availableEUt)
            .setRecipeEUt(RECIPE.eut())
            .setDuration(RECIPE.duration())
            .setParallel(originalMaxParallel);

        maxParallel = GTUtility.longToInt((long) (maxParallel * calculator.calculateMultiplierUnderOneTick()));

        int maxParallelBeforeBatchMode = maxParallel;
        if (isBatchModeEnabled()) {
            maxParallel = GTUtility.longToInt((long) maxParallel * getMaxBatchSize());
        }

        maxParallel = Math.min(maxParallel, GTUtility.longToInt(availableEUt / RECIPE.eut()));

        int currentParallel = 0;
        for (ItemStack item : tInput) {
            ItemStack smeltedOutput = GTModHandler.getSmeltingOutput(item, false, null);

            if (smeltedOutput == null) continue;

            int parallelsLeft = maxParallel - currentParallel;
            if (parallelsLeft <= 0) break;

            currentParallel += Math.min(item.stackSize, parallelsLeft);
        }

        if (currentParallel <= 0) {
            return CheckRecipeResultRegistry.NO_RECIPE;
        }

        int currentParallelBeforeBatchMode = Math.min(currentParallel, maxParallelBeforeBatchMode);
        calculator.setCurrentParallel(currentParallelBeforeBatchMode)
            .calculate();

        double batchMultiplierMax = 1;
        // In case batch mode enabled
        if (currentParallel > maxParallelBeforeBatchMode && calculator.getDuration() < getMaxBatchSize()) {
            batchMultiplierMax = (double) getMaxBatchSize() / calculator.getDuration();
            batchMultiplierMax = Math.min(batchMultiplierMax, (double) currentParallel / maxParallelBeforeBatchMode);
        }

        int finalParallel = (int) (batchMultiplierMax * currentParallelBeforeBatchMode);

        ItemEjectionHelper ejectionHelper = new ItemEjectionHelper(this);

        // Consume items and generate outputs
        HashMap<GTUtility.ItemId, ItemStack> smeltedOutputs = new HashMap<>();
        int toSmelt = finalParallel;

        for (ItemStack item : tInput) {
            ItemStack smeltedOutput = GTModHandler.getSmeltingOutput(item, false, null);

            if (smeltedOutput == null) continue;

            int remainingToSmelt = Math.min(toSmelt, item.stackSize);

            int smeltable = ejectionHelper
                .ejectItems(Collections.singletonList(smeltedOutput.copy()), remainingToSmelt);

            if (smeltable == 0) continue;

            ItemStack outputStack = smeltedOutputs
                .computeIfAbsent(GTUtility.ItemId.create(smeltedOutput), x -> GTUtility.copyAmount(0, smeltedOutput));
            outputStack.stackSize += smeltedOutput.stackSize * smeltable;

            item.stackSize -= smeltable;
            toSmelt -= smeltable;
            if (toSmelt <= 0) break;
        }

        if (smeltedOutputs.isEmpty()) {
            return CheckRecipeResultRegistry.NO_RECIPE;
        }

        this.mOutputItems = smeltedOutputs.values()
            .toArray(new ItemStack[0]);

        this.mEfficiency = 10000 - (getIdealStatus() - getRepairStatus()) * 1000;
        this.mEfficiencyIncrease = 10000;
        this.mMaxProgresstime = (int) (calculator.getDuration() * batchMultiplierMax);
        this.lEUt = Math.min(SPEC.getMaxEuPerTick(inputs), calculator.getConsumption());
        if (this.lEUt > 0) {
            this.lEUt = -this.lEUt;
        }
        this.updateSlots();
        if (this.recipesDone == 0) this.recipesDone++;
        // Multiblock base already includes 1 parallel
        this.recipesDone += finalParallel - 1;
        return CheckRecipeResultRegistry.SUCCESSFUL;
    }

    @Override
    public boolean supportsVoidProtection() {
        return true;
    }

    @Override
    public IStructureDefinition<MTEMultiFurnace> getStructureDefinition() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    public void checkMachine(IGregTechTileEntity aBaseMetaTileEntity, ItemStack aStack, List<StructureError> errors) {
        this.mLevel = 0;
        setCoilLevel(HeatingCoilLevel.None);
        if (!checkPiece(STRUCTURE_PIECE_MAIN, 1, 2, 0, errors)) return;
        if (getCoilLevel() == HeatingCoilLevel.None) {
            errors.add(StructureErrorRegistry.COIL_LEVEL_NOT_ENOUGH);
        } else {
            updateParallel();
        }
        checkHasEnergyHatch(errors);
        checkHasMaintenanceHatch(errors);
        checkHasInputBus(errors);
        checkHasOutputBus(errors);
    }

    private void updateParallel() {
        this.mLevel = SPEC.getMaxParallel(getCurrentProcessingSpecInputs());
    }

    @Override
    public ProcessingSpec getProcessingSpec() {
        return SPEC;
    }

    @Override
    @Nonnull
    public List<Modifier<?>> getModifiersForInspection() {
        return List.of(
            COIL.derivingAfterSet(MTEMultiFurnace::updateParallel)
                .of(this));
    }

    @Override
    public String[] getInfoData() {
        long storedEnergy = 0;
        long maxEnergy = 0;
        for (final MTEHatchEnergy tHatch : validMTEList(mEnergyHatches)) {
            storedEnergy += tHatch.getBaseMetaTileEntity()
                .getStoredEU();
            maxEnergy += tHatch.getBaseMetaTileEntity()
                .getEUCapacity();
        }

        return new String[] {
            IGregTechDeviceInformation.encode(
                "GT5U.multiblock.Progress.fmt.s",
                formatNumber(mProgresstime / 20),
                formatNumber(mMaxProgresstime / 20)),
            IGregTechDeviceInformation
                .encode("GT5U.multiblock.energy.fmt", formatNumber(storedEnergy), formatNumber(maxEnergy)),
            IGregTechDeviceInformation.encode("GT5U.multiblock.usage.fmt", formatNumber(-lEUt)),
            IGregTechDeviceInformation.encode(
                "GT5U.multiblock.mei.fmt.2A",
                formatNumber(getMaxInputVoltage()),
                VN[GTUtility.getTier(getMaxInputVoltage())]),
            IGregTechDeviceInformation.encode(
                "GT5U.multiblock.problems.efficiency.fmt",
                getIdealStatus() - getRepairStatus(),
                mEfficiency / 100.0F + " %"),
            IGregTechDeviceInformation.encode("GT5U.infodata.ms.multismelting", mLevel),
            IGregTechDeviceInformation.encode("GT5U.multiblock.pollution.fmt", getAveragePollutionPercentage()),
            IGregTechDeviceInformation.encode("GT5U.multiblock.recipesDone.fmt", formatNumber(recipesDone)) };
    }

    @Override
    public void construct(ItemStack stackSize, boolean hintsOnly) {
        buildPiece(STRUCTURE_PIECE_MAIN, stackSize, hintsOnly, 1, 2, 0);
    }

    @Override
    public int survivalConstruct(ItemStack stackSize, int elementBudget, ISurvivalBuildEnvironment env) {
        if (mMachine) return -1;
        return survivalBuildPiece(STRUCTURE_PIECE_MAIN, stackSize, 1, 2, 0, elementBudget, env, false, true);
    }

    @Override
    public boolean supportsBatchMode() {
        return true;
    }

    @Override
    public boolean supportsSingleRecipeLocking() {
        return false;
    }
}
