package tectech.thing.metaTileEntity.multi;

import static com.gtnewhorizon.gtnhlib.util.numberformatting.NumberFormatUtil.formatNumber;
import static com.gtnewhorizon.structurelib.structure.StructureUtility.ofBlock;
import static com.gtnewhorizon.structurelib.structure.StructureUtility.ofBlocksTiered;
import static com.gtnewhorizon.structurelib.structure.StructureUtility.transpose;
import static gregtech.api.enums.HatchElement.InputBus;
import static gregtech.api.enums.HatchElement.InputHatch;
import static gregtech.api.enums.HatchElement.OutputBus;
import static gregtech.api.enums.HatchElement.OutputHatch;
import static gregtech.api.util.GTStructureUtility.buildHatchAdder;
import static gregtech.api.util.ParallelHelper.calculateIntegralChancedOutputMultiplier;
import static gregtech.common.misc.WirelessNetworkManager.addEUToGlobalEnergyMap;
import static gregtech.common.misc.WirelessNetworkManager.strongCheckOrAddUser;
import static java.lang.Math.exp;
import static kekztech.util.Util.toStandardForm;
import static net.minecraft.util.EnumChatFormatting.AQUA;
import static net.minecraft.util.EnumChatFormatting.BLUE;
import static net.minecraft.util.EnumChatFormatting.RED;
import static net.minecraft.util.EnumChatFormatting.RESET;
import static net.minecraft.util.EnumChatFormatting.YELLOW;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;

import com.google.common.collect.ImmutableList;
import com.gtnewhorizon.gtnhlib.chat.customcomponents.ChatComponentNumber;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.structure.IItemSource;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.casing.Casings;
import gregtech.api.enums.GTAuthors;
import gregtech.api.enums.Materials;
import gregtech.api.enums.Mods;
import gregtech.api.enums.SoundResource;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.IIconContainer;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechDeviceInformation;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.logic.Modifier;
import gregtech.api.logic.ModifierKind;
import gregtech.api.logic.ProcessingSpec;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.recipe.check.SimpleCheckRecipeResult;
import gregtech.api.structure.error.ErrorType;
import gregtech.api.structure.error.StructureError;
import gregtech.api.structure.error.StructureErrorRegistry;
import gregtech.api.structure.error.StructureErrors;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.api.util.ItemEjectionHelper;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.shutdown.ShutDownReason;
import gregtech.common.misc.GTStructureChannels;
import gregtech.common.tileentities.machines.MTEHatchInputBusME;
import gregtech.common.tileentities.machines.MTEHatchInputME;
import gregtech.common.tileentities.machines.RecipeCheckReason;
import gtPlusPlus.core.util.minecraft.ItemUtils;
import gtneioreplugin.plugin.block.BlockDimensionDisplay;
import gtneioreplugin.plugin.block.ModBlocks;
import tectech.TecTech;
import tectech.recipe.EyeOfHarmonyRecipe;
import tectech.recipe.TecTechRecipeMaps;
import tectech.thing.CustomItemList;
import tectech.thing.block.TileEntityEyeOfHarmony;
import tectech.thing.casing.BlockGTCasingsTT;
import tectech.thing.casing.TTCasingsContainer;
import tectech.thing.metaTileEntity.multi.base.TTMultiblockBase;
import tectech.thing.metaTileEntity.multi.base.render.TTRenderedExtendedFacingTexture;
import tectech.util.CommonValues;
import tectech.util.FluidStackLong;
import tectech.util.ItemStackLong;

@SuppressWarnings("SpellCheckingInspection")
@IMetaTileEntity.SkipGenerateDescription
public class MTEEyeOfHarmony extends TTMultiblockBase implements ISurvivalConstructable {

    public static final boolean EOH_DEBUG_MODE = false;
    private static final long MOLTEN_SPACETIME_PER_FAILURE_TIER = 14_400L;
    private static final double SPACETIME_FAILURE_BASE = 2;

    // Region variables.
    private static IIconContainer ScreenOFF;
    private static IIconContainer ScreenON;

    private int spacetimeCompressionFieldMetadata = -1;
    private int timeAccelerationFieldMetadata = -1;
    private int stabilisationFieldMetadata = -1;

    private static final double SPACETIME_CASING_DIFFERENCE_DISCOUNT_PERCENTAGE = 0.03;
    private static final double TIME_ACCEL_DECREASE_CHANCE_PER_TIER = 0.0925;
    // % Increase in recipe chance and % decrease in yield per tier.
    private static final double STABILITY_INCREASE_PROBABILITY_DECREASE_YIELD_PER_TIER = 0.05;
    private static final double PARALLEL_FOR_FIRST_ASTRAL_ARRAY = 8;
    private static final double CONSTANT_FOR_LOG = 1.7;
    private static final double LOG_CONSTANT = Math.log(CONSTANT_FOR_LOG);
    private static final double PARALLEL_MULTIPLIER_CONSTANT = 1.63;
    private static final double POWER_DIVISION_CONSTANT = 20.7;
    private static final double POWER_INCREASE_CONSTANT = 2.3;
    private static final int TOTAL_CASING_TIERS_WITH_POWER_PENALTY = 8;
    private static final long PRECISION_MULTIPLIER = 1_000_000;
    // Exact value to get 2^21 parallels.
    private static final long ASTRAL_ARRAY_LIMIT = 8637;

    public static final ModifierKind<Integer> SPACETIME_COMPRESSION_FIELD = fieldKind(
        "tectech:spacetime_compression_field",
        "GT5U.MBTT.Tiers.SpacetimeCompressionField");
    public static final ModifierKind<Integer> TIME_DILATION_FIELD = fieldKind(
        "tectech:time_dilation_field",
        "GT5U.MBTT.Tiers.TimeDilationField");
    public static final ModifierKind<Integer> STABILISATION_FIELD = fieldKind(
        "tectech:stabilisation_field",
        "GT5U.MBTT.Tiers.StabilisationField");
    public static final ModifierKind<Integer> ASTRAL_ARRAYS = ModifierKind.ofInt("tectech:astral_arrays")
        .name("GT5U.MBTT.Tiers.AstralArrays")
        .source(ModifierKind.Source.ITEM)
        .ordered()
        .register();
    /** The programmed circuit, 0 to 24: each step halves the time and quadruples the start-up EU. */
    public static final ModifierKind<Integer> CIRCUIT = ModifierKind.ofInt("tectech:eoh_circuit")
        .name("GT5U.MBTT.Tiers.EohCircuit")
        .source(ModifierKind.Source.ITEM)
        .register();
    // Litres drained from the input hatches. Stock above a recipe's requirement lowers its chance and yield.
    public static final ModifierKind<Long> STORED_HYDROGEN = storedKind(
        "tectech:eoh_stored_hydrogen",
        "GT5U.MBTT.Tiers.StoredHydrogen");
    public static final ModifierKind<Long> STORED_HELIUM = storedKind(
        "tectech:eoh_stored_helium",
        "GT5U.MBTT.Tiers.StoredHelium");
    public static final ModifierKind<Long> STORED_STAR_MATTER = storedKind(
        "tectech:eoh_stored_star_matter",
        "GT5U.MBTT.Tiers.StoredStarMatter");
    /** 1 when a single run succeeds for certain after repeated failures, before fluid overflow. */
    public static final ModifierKind<Integer> PITY = ModifierKind.ofInt("tectech:eoh_pity")
        .name("GT5U.MBTT.Tiers.EohPity")
        .source(ModifierKind.Source.RUNTIME)
        .ordered()
        .register();
    // No ProcessingLogic: processRecipe runs the recipe through the spec
    private static final ProcessingSpec SPEC = ProcessingSpec.builder()
        .parallel(in -> (int) parallel(in))
        .noTooltip(ProcessingSpec.Quantity.PARALLEL)
        .durationPerRecipe(
            (in, recipe) -> recipeTicks(
                recipe.mDuration,
                requiredSpacetimeTier(recipe),
                in.value(SPACETIME_COMPRESSION_FIELD),
                in.value(TIME_DILATION_FIELD),
                in.value(CIRCUIT)))
        .noTooltip(ProcessingSpec.Quantity.DURATION)
        .noOverclock()
        .noTooltip(ProcessingSpec.Quantity.OVERCLOCK)
        // parallel runs take star matter, a single run hydrogen and helium
        .requires(
            (in, recipe) -> parallel(in) == 1
                || enough(in.value(STORED_STAR_MATTER), starMatterRequired(eoh(recipe), parallel(in))),
            (in, recipe) -> SimpleCheckRecipeResult.ofFailure("no_stellar_plasma"))
        .requires(
            (in, recipe) -> parallel(in) > 1 || enough(in.value(STORED_HYDROGEN), eoh(recipe).getHydrogenRequirement()),
            (in, recipe) -> SimpleCheckRecipeResult.ofFailure("no_hydrogen"))
        .requires(
            (in, recipe) -> parallel(in) > 1 || enough(in.value(STORED_HELIUM), eoh(recipe).getHeliumRequirement()),
            (in, recipe) -> SimpleCheckRecipeResult.ofFailure("no_helium"))
        .requires(
            (in, recipe) -> in.value(SPACETIME_COMPRESSION_FIELD) >= requiredSpacetimeTier(recipe),
            (in, recipe) -> CheckRecipeResultRegistry.insufficientMachineTier((int) requiredSpacetimeTier(recipe)))
        .euPerRunPerRecipe(MTEEyeOfHarmony::euPerRun)
        .euGeneratedPerRecipe(MTEEyeOfHarmony::euGenerated)
        .successChancePerRecipe(MTEEyeOfHarmony::successChance)
        .outputYieldPerRecipe(MTEEyeOfHarmony::outputYield)
        .noTooltip(ProcessingSpec.Quantity.POWER, ProcessingSpec.Quantity.OUTPUT)
        .build();

    private static EyeOfHarmonyRecipe eoh(GTRecipe recipe) {
        return (EyeOfHarmonyRecipe) recipe.mSpecialItems;
    }

    /** The recipe map's entry, which the spec reads. */
    private static GTRecipe displayRecipe(EyeOfHarmonyRecipe recipe) {
        for (GTRecipe entry : TecTechRecipeMaps.eyeOfHarmonyRecipes.getAllRecipes()) {
            if (entry.mSpecialItems == recipe) return entry;
        }
        throw new IllegalStateException("no recipe map entry for " + recipe.getRecipeTriggerItem());
    }

    private static long parallel(ProcessingSpec.Inputs in) {
        int arrays = in.value(ASTRAL_ARRAYS);
        return arrays == 0 ? 1 : (long) GTUtility.powInt(2, parallelExponent(arrays));
    }

    private static double starMatterRequired(EyeOfHarmonyRecipe recipe, long parallel) {
        return recipe.getHeliumRequirement() * (12.4 / 1_000_000f) * parallel;
    }

    /** Debug mode requires 100 L instead. */
    private static boolean enough(long stored, double required) {
        return EOH_DEBUG_MODE ? stored >= 100 : stored >= required;
    }

    /** 0 at exactly the required amount, rising towards 1 as the stored fluid exceeds it. */
    private static double overflowPenalty(long stored, double required) {
        if (EOH_DEBUG_MODE) return 0;
        double excess = stored / required - 1;
        return 1 - exp(-GTUtility.powInt(30 * excess, 2));
    }

    private static double overflowPenalty(ProcessingSpec.Inputs in, EyeOfHarmonyRecipe recipe) {
        long parallel = parallel(in);
        if (parallel > 1) return overflowPenalty(in.value(STORED_STAR_MATTER), starMatterRequired(recipe, parallel));
        return overflowPenalty(in.value(STORED_HYDROGEN), recipe.getHydrogenRequirement())
            + overflowPenalty(in.value(STORED_HELIUM), recipe.getHeliumRequirement());
    }

    /** Before fluid overflow. */
    private static double baseSuccessChance(EyeOfHarmonyRecipe recipe, int timeDilationTier, int stabilisationTier) {
        return recipe.getBaseRecipeSuccessChance() - timeDilationTier * TIME_ACCEL_DECREASE_CHANCE_PER_TIER
            + stabilisationTier * STABILITY_INCREASE_PROBABILITY_DECREASE_YIELD_PER_TIER;
    }

    private static double successChance(ProcessingSpec.Inputs in, GTRecipe recipe) {
        if (EOH_DEBUG_MODE) return 1;
        double chance = baseSuccessChance(eoh(recipe), in.value(TIME_DILATION_FIELD), in.value(STABILISATION_FIELD));
        if (parallel(in) == 1 && in.value(PITY) == 1) chance = 1;
        return MathHelper.clamp_double(chance - overflowPenalty(in, eoh(recipe)), 0.0, 1.0);
    }

    private static double outputYield(ProcessingSpec.Inputs in, GTRecipe recipe) {
        double yield = 1.0 - in.value(STABILISATION_FIELD) * STABILITY_INCREASE_PROBABILITY_DECREASE_YIELD_PER_TIER;
        return MathHelper.clamp_double(yield - overflowPenalty(in, eoh(recipe)), 0.0, 1.0);
    }

    /** The start cost, taken from the wireless network. */
    private static BigInteger euPerRun(ProcessingSpec.Inputs in, GTRecipe recipe) {
        BigInteger eu = BigInteger.valueOf(eoh(recipe).getEUStartCost())
            .multiply(BigInteger.valueOf((long) GTUtility.powInt(4, in.value(CIRCUIT))));
        if (parallel(in) == 1) return eu;
        return eu
            .multiply(
                BigInteger.valueOf((long) (powerMultiplier(in) * PARALLEL_MULTIPLIER_CONSTANT * PRECISION_MULTIPLIER)))
            .divide(BigInteger.valueOf((long) (PRECISION_MULTIPLIER * POWER_DIVISION_CONSTANT)));
    }

    /** Given to the wireless network. Lower stabilisation fields keep less of it. */
    private static BigInteger euGenerated(ProcessingSpec.Inputs in, GTRecipe recipe) {
        double penalty = (TOTAL_CASING_TIERS_WITH_POWER_PENALTY - in.value(STABILISATION_FIELD))
            * STABILITY_INCREASE_PROBABILITY_DECREASE_YIELD_PER_TIER;
        BigInteger eu = BigInteger.valueOf((long) (eoh(recipe).getEUOutput() * (1 - penalty)));
        if (parallel(in) == 1) return eu;
        return eu.multiply(BigInteger.valueOf((long) (powerMultiplier(in) * PRECISION_MULTIPLIER)))
            .divide(BigInteger.valueOf((long) (PRECISION_MULTIPLIER * POWER_DIVISION_CONSTANT)));
    }

    private static double powerMultiplier(ProcessingSpec.Inputs in) {
        return Math.max(1, GTUtility.powInt(POWER_INCREASE_CONSTANT, parallelExponent(in.value(ASTRAL_ARRAYS))));
    }

    private static ModifierKind<Long> storedKind(String id, String nameKey) {
        return ModifierKind.ofLong(id)
            .name(nameKey)
            .source(ModifierKind.Source.RUNTIME)
            .register();
    }

    private static long requiredSpacetimeTier(GTRecipe recipe) {
        return recipe.mSpecialItems instanceof EyeOfHarmonyRecipe eoh ? eoh.getSpacetimeCasingTierRequired() : 0;
    }

    private static ModifierKind<Integer> fieldKind(String id, String nameKey) {
        return ModifierKind.ofInt(id)
            .name(nameKey)
            .ordered()
            .labels(CommonValues::getLocalizedEohTierFancyNames)
            .register();
    }

    /** 1 without astral arrays. */
    private static long parallelExponent(long astralArrays) {
        if (astralArrays == 0) return 1;
        return (long) Math.floor(
            Math.log(PARALLEL_FOR_FIRST_ASTRAL_ARRAY * Math.min(astralArrays, ASTRAL_ARRAY_LIMIT)) / LOG_CONSTANT);
    }

    private UUID userUUID;
    private BigInteger outputEU_BigInt = BigInteger.ZERO;
    private long startEU = 0;

    @Override
    public int survivalConstruct(ItemStack stackSize, int elementBudget, IItemSource source, EntityPlayerMP actor) {
        if (mMachine) return -1;
        int realBudget = elementBudget >= 200 ? elementBudget : Math.min(200, elementBudget * 5); // 200 blocks max per
                                                                                                  // placement.
        return survivalBuildPiece(STRUCTURE_PIECE_MAIN, stackSize, 16, 16, 0, realBudget, source, actor, false, true);
    }

    protected static final String STRUCTURE_PIECE_MAIN = "main";

    // Multiblock structure.
    private static final IStructureDefinition<MTEEyeOfHarmony> STRUCTURE_DEFINITION = IStructureDefinition
        .<MTEEyeOfHarmony>builder()
        .addShape(
            STRUCTURE_PIECE_MAIN,
            transpose(
                new String[][] {
                    { "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "               C C               ", "               C C               ",
                        "               C C               ", "            CCCCCCCCC            ",
                        "               C C               ", "            CCCCCCCCC            ",
                        "               C C               ", "               C C               ",
                        "               C C               ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "               C C               ",
                        "               C C               ", "               C C               ",
                        "               C C               ", "              DDDDD              ",
                        "             DDCDCDD             ", "         CCCCDCCDCCDCCCC         ",
                        "             DDDDDDD             ", "         CCCCDCCDCCDCCCC         ",
                        "             DDCDCDD             ", "              DDDDD              ",
                        "               C C               ", "               C C               ",
                        "               C C               ", "               C C               ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "               C C               ",
                        "               C C               ", "               C C               ",
                        "                D                ", "                D                ",
                        "             DDDDDDD             ", "            DD     DD            ",
                        "            D  EEE  D            ", "       CCC  D EAAAE D  CCC       ",
                        "          DDD EAAAE DDD          ", "       CCC  D EAAAE D  CCC       ",
                        "            D  EEE  D            ", "            DD     DD            ",
                        "             DDDDDDD             ", "                D                ",
                        "                D                ", "               C C               ",
                        "               C C               ", "               C C               ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "               C C               ", "               C C               ",
                        "                D                ", "                D                ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "      CC                 CC      ",
                        "        DD             DD        ", "      CC                 CC      ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                D                ",
                        "                D                ", "               C C               ",
                        "               C C               ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "               C C               ",
                        "              CCCCC              ", "                D                ",
                        "                A                ", "                A                ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "      C                   C      ", "     CC                   CC     ",
                        "      CDAA             AADC      ", "     CC                   CC     ",
                        "      C                   C      ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                A                ",
                        "                A                ", "                D                ",
                        "              CCCCC              ", "               C C               ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "               C C               ", "               C C               ",
                        "                D                ", "             SEEAEES             ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "       S                 S       ",
                        "       E                 E       ", "    CC E                 E CC    ",
                        "      DA                 AD      ", "    CC E                 E CC    ",
                        "       E                 E       ", "       S                 S       ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "             SEEAEES             ",
                        "                D                ", "               C C               ",
                        "               C C               ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "                                 ", "               C C               ",
                        "              CCCCC              ", "                D                ",
                        "                A                ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "    C                       C    ", "   CC                       CC   ",
                        "    CDA                   ADC    ", "   CC                       CC   ",
                        "    C                       C    ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                A                ", "                D                ",
                        "              CCCCC              ", "               C C               ",
                        "                                 ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "               C C               ", "               C C               ",
                        "                D                ", "             SEEAEES             ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "     S                     S     ",
                        "     E                     E     ", "  CC E                     E CC  ",
                        "    DA                     AD    ", "  CC E                     E CC  ",
                        "     E                     E     ", "     S                     S     ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "             SEEAEES             ",
                        "                D                ", "               C C               ",
                        "               C C               ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "               C C               ", "                D                ",
                        "                A                ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "  C                           C  ",
                        "   DA                       AD   ", "  C                           C  ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                A                ", "                D                ",
                        "               C C               ", "                                 ",
                        "                                 " },
                    { "                                 ", "               C C               ",
                        "               C C               ", "                D                ",
                        "                A                ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", " CC                           CC ",
                        "   DA                       AD   ", " CC                           CC ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                A                ", "                D                ",
                        "               C C               ", "               C C               ",
                        "                                 " },
                    { "                                 ", "               C C               ",
                        "                D                ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", " C                             C ",
                        "  D                           D  ", " C                             C ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                D                ", "               C C               ",
                        "                                 " },
                    { "                                 ", "               C C               ",
                        "                D                ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", " C                             C ",
                        "  D                           D  ", " C                             C ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                D                ", "               C C               ",
                        "                                 " },
                    { "             CCCCCCC             ", "               C C               ",
                        "             DDDDDDD             ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "  D                           D  ",
                        "  D                           D  ", "CCD                           DCC",
                        "  D                           D  ", "CCD                           DCC",
                        "  D                           D  ", "  D                           D  ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "             DDDDDDD             ", "               C C               ",
                        "               C C               " },
                    { "            CCHHHHHCC            ", "              DDDDD              ",
                        "            DD     DD            ", "                                 ",
                        "                                 ", "       S                 S       ",
                        "                                 ", "     S                     S     ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "  D                           D  ", "  D                           D  ",
                        " D                             D ", "CD                             DC",
                        " D                             D ", "CD                             DC",
                        " D                             D ", "  D                           D  ",
                        "  D                           D  ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "     S                     S     ",
                        "                                 ", "       S                 S       ",
                        "                                 ", "                                 ",
                        "            DD     DD            ", "              DDDDD              ",
                        "               C C               " },
                    { "            CHHHHHHHC            ", "             DDCDCDD             ",
                        "            D  EEE  D            ", "                                 ",
                        "      C                   C      ", "       E                 E       ",
                        "    C                       C    ", "     E                     E     ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "  D                           D  ", " D                             D ",
                        " D                             D ", "CCE                           ECC",
                        " DE                           ED ", "CCE                           ECC",
                        " D                             D ", " D                             D ",
                        "  D                           D  ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "     E                     E     ",
                        "    C                       C    ", "       E                 E       ",
                        "      C                   C      ", "                                 ",
                        "            D  EEE  D            ", "             DDCDCDD             ",
                        "               C C               " },
                    { "            CHHCCCHHC            ", "         CCCCDCCDCCDCCCC         ",
                        "       CCC  D EAAAE D  CCC       ", "      CC                 CC      ",
                        "     CC                   CC     ", "    CC E                 E CC    ",
                        "   CC                       CC   ", "  CC E                     E CC  ",
                        "  C                           C  ", " CC                           CC ",
                        " C                             C ", " C                             C ",
                        "CCD                           DCC", "CD                             DC",
                        "CCE                           ECC", "CCA                           ACC",
                        "CDA                           ADC", "CCA                           ACC",
                        "CCE                           ECC", "CD                             DC",
                        "CCD                           DCC", " C                             C ",
                        " C                             C ", " CC                           CC ",
                        "  C                           C  ", "  CC E                     E CC  ",
                        "   CC                       CC   ", "    CC E                 E CC    ",
                        "     CC                   CC     ", "      CC                 CC      ",
                        "       CCC  D EAAAE D  CCC       ", "         CCCCDCCDCCDCCCC         ",
                        "            CCCCCCCCC            " },
                    { "            CHHC~CHHC            ", "             DDDDDDD             ",
                        "          DDD EAAAE DDD          ", "        DD             DD        ",
                        "      CDAA             AADC      ", "      DA                 AD      ",
                        "    CDA                   ADC    ", "    DA                     AD    ",
                        "   DA                       AD   ", "   DA                       AD   ",
                        "  D                           D  ", "  D                           D  ",
                        "  D                           D  ", " D                             D ",
                        " DE                           ED ", "CDA                           ADC",
                        " DA                           AD ", "CDA                           ADC",
                        " DE                           ED ", " D                             D ",
                        "  D                           D  ", "  D                           D  ",
                        "  D                           D  ", "   DA                       AD   ",
                        "   DA                       AD   ", "    DA                     AD    ",
                        "    CDA                   ADC    ", "      DA                 AD      ",
                        "      CDAA             AADC      ", "        DD             DD        ",
                        "          DDD EAAAE DDD          ", "             DDDDDDD             ",
                        "               C C               " },
                    { "            CHHCCCHHC            ", "         CCCCDCCDCCDCCCC         ",
                        "       CCC  D EAAAE D  CCC       ", "      CC                 CC      ",
                        "     CC                   CC     ", "    CC E                 E CC    ",
                        "   CC                       CC   ", "  CC E                     E CC  ",
                        "  C                           C  ", " CC                           CC ",
                        " C                             C ", " C                             C ",
                        "CCD                           DCC", "CD                             DC",
                        "CCE                           ECC", "CCA                           ACC",
                        "CDA                           ADC", "CCA                           ACC",
                        "CCE                           ECC", "CD                             DC",
                        "CCD                           DCC", " C                             C ",
                        " C                             C ", " CC                           CC ",
                        "  C                           C  ", "  CC E                     E CC  ",
                        "   CC                       CC   ", "    CC E                 E CC    ",
                        "     CC                   CC     ", "      CC                 CC      ",
                        "       CCC  D EAAAE D  CCC       ", "         CCCCDCCDCCDCCCC         ",
                        "            CCCCCCCCC            " },
                    { "            CHHHHHHHC            ", "             DDCDCDD             ",
                        "            D  EEE  D            ", "                                 ",
                        "      C                   C      ", "       E                 E       ",
                        "    C                       C    ", "     E                     E     ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "  D                           D  ", " D                             D ",
                        " D                             D ", "CCE                           ECC",
                        " DE                           ED ", "CCE                           ECC",
                        " D                             D ", " D                             D ",
                        "  D                           D  ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "     E                     E     ",
                        "    C                       C    ", "       E                 E       ",
                        "      C                   C      ", "                                 ",
                        "            D  EEE  D            ", "             DDCDCDD             ",
                        "               C C               " },
                    { "            CCHHHHHCC            ", "              DDDDD              ",
                        "            DD     DD            ", "                                 ",
                        "                                 ", "       S                 S       ",
                        "                                 ", "     S                     S     ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "  D                           D  ", "  D                           D  ",
                        " D                             D ", "CD                             DC",
                        " D                             D ", "CD                             DC",
                        " D                             D ", "  D                           D  ",
                        "  D                           D  ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "     S                     S     ",
                        "                                 ", "       S                 S       ",
                        "                                 ", "                                 ",
                        "            DD     DD            ", "              DDDDD              ",
                        "               C C               " },
                    { "             CCCCCCC             ", "               C C               ",
                        "             DDDDDDD             ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "  D                           D  ",
                        "  D                           D  ", "CCD                           DCC",
                        "  D                           D  ", "CCD                           DCC",
                        "  D                           D  ", "  D                           D  ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "             DDDDDDD             ", "               C C               ",
                        "               C C               " },
                    { "                                 ", "               C C               ",
                        "                D                ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", " C                             C ",
                        "  D                           D  ", " C                             C ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                D                ", "               C C               ",
                        "                                 " },
                    { "                                 ", "               C C               ",
                        "                D                ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", " C                             C ",
                        "  D                           D  ", " C                             C ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                D                ", "               C C               ",
                        "                                 " },
                    { "                                 ", "               C C               ",
                        "               C C               ", "                D                ",
                        "                A                ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", " CC                           CC ",
                        "   DA                       AD   ", " CC                           CC ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                A                ", "                D                ",
                        "               C C               ", "               C C               ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "               C C               ", "                D                ",
                        "                A                ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "  C                           C  ",
                        "   DA                       AD   ", "  C                           C  ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                A                ", "                D                ",
                        "               C C               ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "               C C               ", "               C C               ",
                        "                D                ", "             SEEAEES             ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "     S                     S     ",
                        "     E                     E     ", "  CC E                     E CC  ",
                        "    DA                     AD    ", "  CC E                     E CC  ",
                        "     E                     E     ", "     S                     S     ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "             SEEAEES             ",
                        "                D                ", "               C C               ",
                        "               C C               ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "                                 ", "               C C               ",
                        "              CCCCC              ", "                D                ",
                        "                A                ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "    C                       C    ", "   CC                       CC   ",
                        "    CDA                   ADC    ", "   CC                       CC   ",
                        "    C                       C    ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                A                ", "                D                ",
                        "              CCCCC              ", "               C C               ",
                        "                                 ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "               C C               ", "               C C               ",
                        "                D                ", "             SEEAEES             ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "       S                 S       ",
                        "       E                 E       ", "    CC E                 E CC    ",
                        "      DA                 AD      ", "    CC E                 E CC    ",
                        "       E                 E       ", "       S                 S       ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "             SEEAEES             ",
                        "                D                ", "               C C               ",
                        "               C C               ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "               C C               ",
                        "              CCCCC              ", "                D                ",
                        "                A                ", "                A                ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "      C                   C      ", "     CC                   CC     ",
                        "      CDAA             AADC      ", "     CC                   CC     ",
                        "      C                   C      ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                A                ",
                        "                A                ", "                D                ",
                        "              CCCCC              ", "               C C               ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "               C C               ", "               C C               ",
                        "                D                ", "                D                ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "      CC                 CC      ",
                        "        DD             DD        ", "      CC                 CC      ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                D                ",
                        "                D                ", "               C C               ",
                        "               C C               ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "               C C               ",
                        "               C C               ", "               C C               ",
                        "                D                ", "                D                ",
                        "             DDDDDDD             ", "            DD     DD            ",
                        "            D  EEE  D            ", "       CCC  D EAAAE D  CCC       ",
                        "          DDD EAAAE DDD          ", "       CCC  D EAAAE D  CCC       ",
                        "            D  EEE  D            ", "            DD     DD            ",
                        "             DDDDDDD             ", "                D                ",
                        "                D                ", "               C C               ",
                        "               C C               ", "               C C               ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "               C C               ",
                        "               C C               ", "               C C               ",
                        "               C C               ", "              DDDDD              ",
                        "             DDCDCDD             ", "         CCCCDCCDCCDCCCC         ",
                        "             DDDDDDD             ", "         CCCCDCCDCCDCCCC         ",
                        "             DDCDCDD             ", "              DDDDD              ",
                        "               C C               ", "               C C               ",
                        "               C C               ", "               C C               ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 " },
                    { "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "               C C               ", "               C C               ",
                        "               C C               ", "            CCCCCCCCC            ",
                        "               C C               ", "            CCCCCCCCC            ",
                        "               C C               ", "               C C               ",
                        "               C C               ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 ", "                                 ",
                        "                                 " } }))
        .addElement(
            'A',
            GTStructureChannels.EOH_COMPRESSION.use(
                ofBlocksTiered(
                    (block, meta) -> block == TTCasingsContainer.SpacetimeCompressionFieldGenerators ? meta : null,
                    ImmutableList.of(
                        Pair.of(TTCasingsContainer.SpacetimeCompressionFieldGenerators, 0),
                        Pair.of(TTCasingsContainer.SpacetimeCompressionFieldGenerators, 1),
                        Pair.of(TTCasingsContainer.SpacetimeCompressionFieldGenerators, 2),
                        Pair.of(TTCasingsContainer.SpacetimeCompressionFieldGenerators, 3),
                        Pair.of(TTCasingsContainer.SpacetimeCompressionFieldGenerators, 4),
                        Pair.of(TTCasingsContainer.SpacetimeCompressionFieldGenerators, 5),
                        Pair.of(TTCasingsContainer.SpacetimeCompressionFieldGenerators, 6),
                        Pair.of(TTCasingsContainer.SpacetimeCompressionFieldGenerators, 7),
                        Pair.of(TTCasingsContainer.SpacetimeCompressionFieldGenerators, 8)),
                    -1,
                    (t, meta) -> t.spacetimeCompressionFieldMetadata = meta,
                    t -> t.spacetimeCompressionFieldMetadata)))
        .addElement(
            'S',
            GTStructureChannels.EOH_STABILISATION.use(
                ofBlocksTiered(
                    (block, meta) -> block == TTCasingsContainer.StabilisationFieldGenerators ? meta : null,
                    ImmutableList.of(
                        Pair.of(TTCasingsContainer.StabilisationFieldGenerators, 0),
                        Pair.of(TTCasingsContainer.StabilisationFieldGenerators, 1),
                        Pair.of(TTCasingsContainer.StabilisationFieldGenerators, 2),
                        Pair.of(TTCasingsContainer.StabilisationFieldGenerators, 3),
                        Pair.of(TTCasingsContainer.StabilisationFieldGenerators, 4),
                        Pair.of(TTCasingsContainer.StabilisationFieldGenerators, 5),
                        Pair.of(TTCasingsContainer.StabilisationFieldGenerators, 6),
                        Pair.of(TTCasingsContainer.StabilisationFieldGenerators, 7),
                        Pair.of(TTCasingsContainer.StabilisationFieldGenerators, 8)),
                    -1,
                    (t, meta) -> t.stabilisationFieldMetadata = meta,
                    t -> t.stabilisationFieldMetadata)))
        .addElement('C', ofBlock(TTCasingsContainer.sBlockCasingsBA0, 11))
        .addElement('D', ofBlock(TTCasingsContainer.sBlockCasingsBA0, 10))
        .addElement(
            'H',
            buildHatchAdder(MTEEyeOfHarmony.class).atLeast(InputBus, InputHatch, InputHatch, OutputBus, OutputHatch)
                .casingIndex(Casings.InfiniteSpacetimeEnergyBoundaryCasing.getTextureId())
                .hint(1)
                .buildAndChain(Casings.InfiniteSpacetimeEnergyBoundaryCasing.asElement()))
        .addElement(
            'E',
            GTStructureChannels.EOH_DILATION.use(
                ofBlocksTiered(
                    (block, meta) -> block == TTCasingsContainer.TimeAccelerationFieldGenerator ? meta : null,
                    ImmutableList.of(
                        Pair.of(TTCasingsContainer.TimeAccelerationFieldGenerator, 0),
                        Pair.of(TTCasingsContainer.TimeAccelerationFieldGenerator, 1),
                        Pair.of(TTCasingsContainer.TimeAccelerationFieldGenerator, 2),
                        Pair.of(TTCasingsContainer.TimeAccelerationFieldGenerator, 3),
                        Pair.of(TTCasingsContainer.TimeAccelerationFieldGenerator, 4),
                        Pair.of(TTCasingsContainer.TimeAccelerationFieldGenerator, 5),
                        Pair.of(TTCasingsContainer.TimeAccelerationFieldGenerator, 6),
                        Pair.of(TTCasingsContainer.TimeAccelerationFieldGenerator, 7),
                        Pair.of(TTCasingsContainer.TimeAccelerationFieldGenerator, 8)),
                    -1,
                    (t, meta) -> t.timeAccelerationFieldMetadata = meta,
                    t -> t.timeAccelerationFieldMetadata)))
        .build();

    private static final long TICKS_BETWEEN_HATCH_DRAIN = EOH_DEBUG_MODE ? 10 : 20;

    private List<ItemStackLong> outputItems = new ArrayList<>();
    private List<FluidStackLong> outputFluids = new ArrayList<>();

    private static int recipeTicks(long recipeTime, long recipeSpacetimeCasingRequired, long spacetimeTier,
        long timeDilationTier, long circuit) {

        // Tier 1 recipe.
        // Tier 2 spacetime blocks.
        // = 3% discount.

        // Tier 1 recipe.
        // Tier 3 spacetime blocks.
        // = 3%*3% = 5.91% discount.

        final long spacetimeCasingDifference = (recipeSpacetimeCasingRequired - spacetimeTier);
        final double recipeTimeDiscounted = recipeTime * GTUtility.powInt(2.0, -timeDilationTier)
            * GTUtility.powInt(1 - SPACETIME_CASING_DIFFERENCE_DISCOUNT_PERCENTAGE, -spacetimeCasingDifference)
            * Math.min(1, GTUtility.powInt(2, -circuit));
        return (int) Math.max(recipeTimeDiscounted, 1.0);
    }

    @Override
    public IStructureDefinition<MTEEyeOfHarmony> getStructure_EM() {
        return STRUCTURE_DEFINITION;
    }

    @Override
    public ProcessingSpec getProcessingSpec() {
        return SPEC;
    }

    @Override
    @Nonnull
    public List<Modifier<?>> getModifiersForInspection() {
        return List.of(
            field(
                SPACETIME_COMPRESSION_FIELD,
                () -> spacetimeCompressionFieldMetadata,
                value -> spacetimeCompressionFieldMetadata = value),
            field(
                TIME_DILATION_FIELD,
                () -> timeAccelerationFieldMetadata,
                value -> timeAccelerationFieldMetadata = value),
            field(STABILISATION_FIELD, () -> stabilisationFieldMetadata, value -> stabilisationFieldMetadata = value),
            Modifier.builder(ASTRAL_ARRAYS)
                .between(0, (int) ASTRAL_ARRAY_LIMIT)
                .getter(() -> (int) astralArrayAmount)
                .setter(value -> astralArrayAmount = value)
                .build(),
            Modifier.builder(CIRCUIT)
                .between(0, 24)
                .getter(() -> (int) currentCircuitMultiplier)
                .setter(value -> currentCircuitMultiplier = value)
                .build(),
            stored(STORED_HYDROGEN, Materials.Hydrogen.mGas),
            stored(STORED_HELIUM, Materials.Helium.mGas),
            stored(STORED_STAR_MATTER, Materials.RawStarMatter.mFluid),
            Modifier.builder(PITY)
                .between(0, 1)
                .getter(() -> pityGuaranteed ? 1 : 0)
                .setter(value -> pityGuaranteed = value == 1)
                .build());
    }

    private Modifier<Long> stored(ModifierKind<Long> kind, Fluid fluid) {
        return Modifier.builder(kind)
            .between(0L, Long.MAX_VALUE)
            .getter(() -> validFluidMap.get(fluid))
            .setter(value -> validFluidMap.put(fluid, value))
            .build();
    }

    private static Modifier<Integer> field(ModifierKind<Integer> kind, Supplier<Integer> getter,
        Consumer<Integer> setter) {
        return Modifier.builder(kind)
            .between(0, 8)
            .getter(getter)
            .setter(setter)
            .build();
    }

    public MTEEyeOfHarmony(int aID, String aName, String aNameRegional) {
        super(aID, aName, aNameRegional);
    }

    public MTEEyeOfHarmony(String aName) {
        super(aName);
    }

    @Override
    public IMetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTEEyeOfHarmony(mName);
    }

    @Override
    public void checkMachine(IGregTechTileEntity iGregTechTileEntity, ItemStack itemStack,
        List<StructureError> errors) {

        spacetimeCompressionFieldMetadata = -1;
        timeAccelerationFieldMetadata = -1;
        stabilisationFieldMetadata = -1;

        // Check structure of multi.
        if (!checkPiece(STRUCTURE_PIECE_MAIN, 16, 16, 0, errors)) return;

        // Make sure there are no Crafting Input Buffers/Buses/Slaves.
        if (!mDualInputHatches.isEmpty()) {
            errors.add(StructureErrors.of("GT5U.gui.text.structure_error.crib_not_allowed"));
        }

        // Make sure there are no energy hatches.
        if (!mEnergyHatches.isEmpty() || !mExoticEnergyHatches.isEmpty()) {
            errors.add(StructureErrorRegistry.NO_ENERGY_HATCH_NEEDED);
        }

        // Check there is 1 input bus, and it is not a stocking input bus.
        {
            if (mInputBusses.size() != 1) {
                errors.add(StructureErrors.hatchCount(ErrorType.NOT_MATCH, InputBus, mInputBusses.size(), 1));
            } else if (mInputBusses.get(0) instanceof MTEHatchInputBusME) {
                errors.add(StructureErrors.of("GT5U.gui.text.structure_error.stocking_input_bus_not_allowed"));
            }
        }

        if (mInputHatches.size() != 2) {
            errors.add(StructureErrors.hatchCount(ErrorType.NOT_MATCH, InputHatch, mInputHatches.size(), 2));
        }
        for (MTEHatchInput inputHatch : mInputHatches) {
            if (inputHatch instanceof MTEHatchInputME) {
                errors.add(StructureErrors.of("GT5U.gui.text.structure_error.stocking_input_hatch_not_allowed"));
                break;
            }
        }
        checkOneOutputBus(errors);
        checkOneOutputHatch(errors);
    }

    private boolean animationsEnabled = true;

    @Override
    public final void onScrewdriverRightClick(ForgeDirection side, EntityPlayer aPlayer, float aX, float aY, float aZ,
        ItemStack aTool) {
        animationsEnabled = !animationsEnabled;
        GTUtility.sendChatTrans(aPlayer, "GT5U.machines.animations." + (animationsEnabled ? "enabled" : "disabled"));
    }

    @Override
    public boolean onWireCutterRightClick(ForgeDirection side, ForgeDirection wrenchingSide, EntityPlayer aPlayer,
        float aX, float aY, float aZ, ItemStack aTool) {
        if (astralArrayAmount != 0) {
            if (recipeRunning) {
                GTUtility.sendChatTrans(aPlayer, "eoh.rightclick.wirecutter.1");
            } else {
                long originalAmount = astralArrayAmount;
                while (astralArrayAmount >= 64) {
                    if (aPlayer.inventory.getFirstEmptyStack() != -1) {
                        aPlayer.inventory.addItemStackToInventory(CustomItemList.astralArrayFabricator.get(64));
                        astralArrayAmount -= 64;
                    } else {
                        break;
                    }
                }
                if (aPlayer.inventory.getFirstEmptyStack() != -1) {
                    aPlayer.inventory
                        .addItemStackToInventory(CustomItemList.astralArrayFabricator.get(astralArrayAmount));
                    astralArrayAmount = 0;
                }
                if (originalAmount - astralArrayAmount > 0) {
                    GTUtility.sendChatTrans(
                        aPlayer,
                        "eoh.rightclick.wirecutter.2",
                        new ChatComponentNumber(originalAmount - astralArrayAmount));
                }
            }
        }
        return true;
    }

    @Override
    public boolean onRightclick(IGregTechTileEntity aBaseMetaTileEntity, EntityPlayer aPlayer) {
        if (getControllerSlot() == null) {
            ItemStack heldItem = aPlayer.getHeldItem();
            if (GTUtility.getBlockFromStack(heldItem) instanceof BlockDimensionDisplay) {
                mInventory[getControllerSlotIndex()] = heldItem.copy();
                mInventory[getControllerSlotIndex()].stackSize = 1;
                aPlayer.setCurrentItemOrArmor(0, ItemUtils.depleteStack(heldItem, 1));
                return true;
            }
        }
        return super.onRightclick(aBaseMetaTileEntity, aPlayer);
    }

    @Override
    public MultiblockTooltipBuilder createTooltip() {
        final MultiblockTooltipBuilder tt = new MultiblockTooltipBuilder();
        // spotless:off
        tt.addMachineType(StatCollector.translateToLocal("gt.mbtt.machine_type.spacetime_manipulator"))
            .addMarkdown(new ResourceLocation("gregtech", "eye-of-harmony"))
            .beginStructureBlock(33, 33, 33, false)
            .addController(StatCollector.translateToLocal("gt.mbtt.structure.front_center_17th_layer"))
            .addCasing("896", new ItemStack(TTCasingsContainer.sBlockCasingsBA0, 1, 11).getDisplayName(), false)
            .addCasing("534", new ItemStack(TTCasingsContainer.sBlockCasingsBA0, 1, 10).getDisplayName(), false)
            .addCasing("168", TTCasingsContainer.TimeAccelerationFieldGenerator.getLocalizedName(), true)
            .addCasing("138", TTCasingsContainer.SpacetimeCompressionFieldGenerators.getLocalizedName(), true)
            .addCasing("48", TTCasingsContainer.StabilisationFieldGenerators.getLocalizedName(), true)
            .addCasing("31", Casings.InfiniteSpacetimeEnergyBoundaryCasing.getLocalizedName(), false)
            .addInputBus("1", StatCollector.translateToLocal("GT5U.tooltip.eye-of-harmony.boundary-no-stocking-bus"), 1)
            .addInputHatch("2", StatCollector.translateToLocal("GT5U.tooltip.eye-of-harmony.boundary-no-stocking-hatch"), 1)
            .addOutputBus("1", StatCollector.translateToLocal("GT5U.tooltip.eye-of-harmony.any-boundary-casing"), 1)
            .addOutputHatch("1", StatCollector.translateToLocal("GT5U.tooltip.eye-of-harmony.any-boundary-casing"), 1)
            .addStructureInfo("")
            .addSubChannel(GTStructureChannels.EOH_STABILISATION)
            .addSubChannel(GTStructureChannels.EOH_DILATION)
            .addSubChannel(GTStructureChannels.EOH_COMPRESSION)
            .toolTipFinisher(EnumChatFormatting.GOLD, 87, GTAuthors.AuthorColen);
        // spotless:on
        return tt;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister aBlockIconRegister) {
        ScreenOFF = Textures.BlockIcons.custom(Mods.GregTech.resourceDomain, "iconsets/EM_BHG");
        ScreenON = Textures.BlockIcons.custom(Mods.GregTech.resourceDomain, "iconsets/EM_BHG_ACTIVE");
        super.registerIcons(aBlockIconRegister);
    }

    @Override
    public ITexture[] getTexture(IGregTechTileEntity aBaseMetaTileEntity, ForgeDirection side, ForgeDirection facing,
        int colorIndex, boolean aActive, boolean aRedstone) {
        if (side == facing) {
            return new ITexture[] { Textures.BlockIcons.casingTexturePages[BlockGTCasingsTT.texturePage][12],
                new TTRenderedExtendedFacingTexture(aActive ? ScreenON : ScreenOFF) };
        }
        return new ITexture[] { Textures.BlockIcons.casingTexturePages[BlockGTCasingsTT.texturePage][12] };
    }

    @Override
    public void construct(ItemStack stackSize, boolean hintsOnly) {
        buildPiece(STRUCTURE_PIECE_MAIN, stackSize, hintsOnly, 16, 16, 0);
    }

    private final Map<Fluid, Long> validFluidMap = new HashMap<>() {

        private static final long serialVersionUID = -8452610443191188130L;

        {
            put(Materials.Hydrogen.mGas, 0L);
            put(Materials.Helium.mGas, 0L);
            put(Materials.RawStarMatter.mFluid, 0L);
        }
    };

    private void drainFluidFromHatchesAndStoreInternally() {
        List<FluidStack> fluidStacks = getStoredFluids();
        for (FluidStack fluidStack : fluidStacks) {
            if (validFluidMap.containsKey(fluidStack.getFluid())) {
                validFluidMap.merge(fluidStack.getFluid(), (long) fluidStack.amount, Long::sum);
                fluidStack.amount = 0;
            }
        }
        updateSlots();
    }

    @Override
    public RecipeMap<?> getRecipeMap() {
        // Only for visual
        return TecTechRecipeMaps.eyeOfHarmonyRecipes;
    }

    private EyeOfHarmonyRecipe currentRecipe;

    private boolean shouldDrainHatches = true;
    private boolean justDrainedHatches = false;
    private long currentCircuitMultiplier = 0;
    private long astralArrayAmount = 0;
    private long parallelAmount = 1;
    private boolean pityGuaranteed;
    private long successfulParallelAmount = 0;
    private double yield = 0;
    private BigInteger usedEU = BigInteger.ZERO;
    private FluidStackLong stellarPlasma;
    private FluidStackLong starMatter;

    @Override
    public void scheduleRecipeCheck(RecipeCheckReason reason) {
        super.scheduleRecipeCheck(reason);
        // Input hatch sends a recipe check schedule
        // we will reschedule again when the fluids are drained from the hatches
        shouldDrainHatches = true;
    }

    @Override
    @NotNull
    protected CheckRecipeResult checkProcessing_EM() {
        ItemStack controllerStack = getControllerSlot();
        if (controllerStack == null) {
            return SimpleCheckRecipeResult.ofFailure("no_planet_block");
        }

        // Delay recipe-check until hatches are drained
        if (!justDrainedHatches) {
            return checkRecipeResult;
        }

        justDrainedHatches = false;

        currentRecipe = TecTech.eyeOfHarmonyRecipeStorage.recipeLookUp(controllerStack);
        if (currentRecipe == null) {
            return CheckRecipeResultRegistry.NO_RECIPE;
        }
        CheckRecipeResult result = processRecipe(currentRecipe);
        if (!result.wasSuccessful()) currentRecipe = null;
        return result;
    }

    private long getHydrogenStored() {
        return validFluidMap.get(Materials.Hydrogen.mGas);
    }

    private long getHeliumStored() {
        return validFluidMap.get(Materials.Helium.mGas);
    }

    private long getStellarPlasmaStored() {
        return validFluidMap.get(Materials.RawStarMatter.mFluid);
    }

    public CheckRecipeResult processRecipe(EyeOfHarmonyRecipe recipeObject) {

        // Get circuit damage, clamp it and then use it later for overclocking.
        currentCircuitMultiplier = 0;
        for (ItemStack itemStack : mInputBusses.get(0)
            .getRealInventory()) {
            if (GTUtility.isAnyIntegratedCircuit(itemStack)) {
                currentCircuitMultiplier = MathHelper.clamp_int(itemStack.getItemDamage(), 0, 24);
                break;
            }
        }

        for (ItemStack itemStack : mInputBusses.get(0)
            .getRealInventory()) {
            if (astralArrayAmount >= ASTRAL_ARRAY_LIMIT) break;
            if (itemStack != null && itemStack.isItemEqual(CustomItemList.astralArrayFabricator.get(1))) {
                long insertAmount = Math.min(itemStack.stackSize, ASTRAL_ARRAY_LIMIT - astralArrayAmount);
                astralArrayAmount += insertAmount;
                itemStack.stackSize -= insertAmount;
            }
        }

        double baseChance = baseSuccessChance(recipeObject, timeAccelerationFieldMetadata, stabilisationFieldMetadata);
        double pityChanceForRecipe = pityChance == Double.MIN_VALUE ? baseChance : pityChance;
        // Intentional: pity compares the base recipe chance before overflow penalties are applied.
        pityGuaranteed = baseChance == successChance && pityChanceForRecipe >= 1;

        ProcessingSpec.Inputs inputs = getCurrentProcessingSpecInputs();
        parallelAmount = SPEC.getMaxParallel(inputs);
        ProcessingSpec.Run run = SPEC.calculate(displayRecipe(recipeObject), inputs);
        if (!run.result()
            .wasSuccessful()) return run.result();

        startEU = recipeObject.getEUStartCost();
        outputEU_BigInt = run.euGeneratedPerRun();
        usedEU = run.euPerRun()
            .negate();

        // Remove EU from the users network.
        if (!addEUToGlobalEnergyMap(userUUID, usedEU)) {
            return CheckRecipeResultRegistry.insufficientStartupPower(usedEU.abs());
        }

        mMaxProgresstime = run.ticks();

        // If pityChance needs to be reset, it will be set to Double.MIN_Value at the end of the recipe.
        if (pityChance == Double.MIN_VALUE) {
            pityChance = baseChance;
        }

        previousRecipeChance = successChance;
        successChance = run.successChance();
        currentRecipeRocketTier = currentRecipe.getRocketTier();

        // Reduce internal storage by input fluid quantity required for recipe.
        if (parallelAmount > 1) {
            validFluidMap.put(Materials.RawStarMatter.mFluid, 0L);
        } else {
            validFluidMap.put(Materials.Hydrogen.mGas, 0L);
            validFluidMap.put(Materials.Helium.mGas, 0L);
        }

        yield = run.outputYield();

        // Return copies of the output objects.
        outputFluids = recipeObject.getOutputFluids();
        outputItems = recipeObject.getOutputItems();

        // Star matter is always the last element in the array.
        starMatter = new FluidStackLong(outputFluids.get(outputFluids.size() - 1));

        // And stellar plasma is the second last.
        stellarPlasma = new FluidStackLong(outputFluids.get(outputFluids.size() - 2));

        successfulParallelAmount = calculateIntegralChancedOutputMultiplier(
            (int) (10000 * successChance),
            (int) parallelAmount);
        // Iterate over item output list and apply yield & successful parallel values.
        for (ItemStackLong itemStackLong : outputItems) {
            itemStackLong.stackSize *= yield * successfulParallelAmount;
        }

        // Iterate over fluid output list and apply yield & successful parallel values.
        for (FluidStackLong fluidStackLong : outputFluids) {
            fluidStackLong.amount *= yield * successfulParallelAmount;
        }

        updateSlots();

        if (animationsEnabled) {
            createRenderBlock(currentRecipe);
        }

        recipeRunning = true;
        return CheckRecipeResultRegistry.SUCCESSFUL;
    }

    private void createRenderBlock(final EyeOfHarmonyRecipe currentRecipe) {

        IGregTechTileEntity gregTechTileEntity = this.getBaseMetaTileEntity();

        int x = gregTechTileEntity.getXCoord();
        int y = gregTechTileEntity.getYCoord();
        int z = gregTechTileEntity.getZCoord();

        double xOffset = 16 * getExtendedFacing().getRelativeBackInWorld().offsetX;
        double zOffset = 16 * getExtendedFacing().getRelativeBackInWorld().offsetZ;
        double yOffset = 16 * getExtendedFacing().getRelativeBackInWorld().offsetY;

        this.getBaseMetaTileEntity()
            .getWorld()
            .setBlock((int) (x + xOffset), (int) (y + yOffset), (int) (z + zOffset), Blocks.air);
        this.getBaseMetaTileEntity()
            .getWorld()
            .setBlock(
                (int) (x + xOffset),
                (int) (y + yOffset),
                (int) (z + zOffset),
                TTCasingsContainer.eyeOfHarmonyRenderBlock);
        TileEntityEyeOfHarmony rendererTileEntity = (TileEntityEyeOfHarmony) this.getBaseMetaTileEntity()
            .getWorld()
            .getTileEntity((int) (x + xOffset), (int) (y + yOffset), (int) (z + zOffset));

        rendererTileEntity.setTier(currentRecipe.getRocketTier());

        int recipeSpacetimeTier = (int) currentRecipe.getSpacetimeCasingTierRequired();

        // Star is a larger size depending on the spacetime tier of the recipe.
        rendererTileEntity.setStarSize(0.4 + recipeSpacetimeTier / 8.0);
    }

    private double successChance;
    private double pityChance;
    private double previousRecipeChance;
    private long currentRecipeRocketTier;

    private void outputFailedChance() {
        long failedParallelAmount = parallelAmount - successfulParallelAmount;
        if (failedParallelAmount > 0) {
            // 2^Tier spacetime released upon recipe failure.
            outputFluidToAENetwork(
                Materials.SpaceTime.getMolten(1),
                (long) ((successChance * MOLTEN_SPACETIME_PER_FAILURE_TIER
                    * GTUtility.powInt(SPACETIME_FAILURE_BASE, currentRecipeRocketTier + 1)) * failedParallelAmount));
            if (parallelAmount == 1) {
                // Add chance to pity if previous recipe is equal to current one, else reset pity
                if (previousRecipeChance == successChance) {
                    pityChance += (1 - successChance) * successChance;
                } else {
                    pityChance = successChance;
                }
            }
        } else if (parallelAmount == 1) {
            // Recipe succeeded, reset pity
            // Set to Double.MIN_VALUE here and actually reset this when the recipe starts.
            pityChance = Double.MIN_VALUE;
        }
        super.outputAfterRecipe_EM();
    }

    @Override
    public void stopMachine(@Nonnull ShutDownReason reason) {
        super.stopMachine(reason);
        destroyRenderBlock();
        recipeRunning = false;
    }

    @Override
    public void onBlockDestroyed() {
        super.onBlockDestroyed();
        destroyRenderBlock();
    }

    private void destroyRenderBlock() {
        IGregTechTileEntity gregTechTileEntity = this.getBaseMetaTileEntity();

        int x = gregTechTileEntity.getXCoord();
        int y = gregTechTileEntity.getYCoord();
        int z = gregTechTileEntity.getZCoord();

        double xOffset = 16 * getExtendedFacing().getRelativeBackInWorld().offsetX;
        double zOffset = 16 * getExtendedFacing().getRelativeBackInWorld().offsetZ;
        double yOffset = 16 * getExtendedFacing().getRelativeBackInWorld().offsetY;

        this.getBaseMetaTileEntity()
            .getWorld()
            .setBlock((int) (x + xOffset), (int) (y + yOffset), (int) (z + zOffset), Blocks.air);
    }

    @Override
    public void outputAfterRecipe_EM() {
        recipeRunning = false;
        eRequiredData = 0L;

        destroyRenderBlock();

        // Output EU
        addEUToGlobalEnergyMap(userUUID, outputEU_BigInt);

        startEU = 0;
        outputEU_BigInt = BigInteger.ZERO;

        outputFailedChance();

        if (successfulParallelAmount > 0) {
            for (ItemStackLong itemStack : outputItems) {
                outputItemToAENetwork(itemStack.itemStack, itemStack.stackSize);
            }

            for (FluidStackLong fluidStack : outputFluids) {
                outputFluidToAENetwork(fluidStack.fluidStack, fluidStack.amount);
            }
        }

        // Clear the array list for new recipes.
        outputItems = new ArrayList<>();
        outputFluids = new ArrayList<>();

        // Do other stuff from TT superclasses. E.g. outputting fluids.
        super.outputAfterRecipe_EM();

        // Schedule drain after output
        shouldDrainHatches = true;
    }

    @Override
    public void onPreTick(IGregTechTileEntity aBaseMetaTileEntity, long aTick) {
        super.onPreTick(aBaseMetaTileEntity, aTick);

        if (aTick == 1) {
            userUUID = getBaseMetaTileEntity().getOwnerUuid();
            strongCheckOrAddUser(userUUID);
        }

        if (!recipeRunning && mMachine) {
            if ((aTick % TICKS_BETWEEN_HATCH_DRAIN) == 0 && shouldDrainHatches) {
                drainFluidFromHatchesAndStoreInternally();
                scheduleRecipeCheckImmediate();
                shouldDrainHatches = false;
                justDrainedHatches = true;
            }
        }
    }

    private boolean recipeRunning = false;

    private void outputItemToAENetwork(ItemStack item, long amount) {
        if (item == null || amount <= 0) return;

        ItemEjectionHelper ejectionHelper = new ItemEjectionHelper(this);

        for (long i = 0; i < amount; i += Integer.MAX_VALUE) {
            int xfer = (int) Math.min(amount - i, Integer.MAX_VALUE);

            ejectionHelper.ejectStack(GTUtility.copyAmountUnsafe(xfer, item));
        }

        ejectionHelper.commit();
    }

    private void outputFluidToAENetwork(FluidStack fluid, long amount) {
        if (fluid == null || amount <= 0) return;

        addFluidOutputs(GTUtility.splitFluidStack(fluid, amount), mOutputHatches);
    }

    @Override
    public String[] getInfoData() {
        ArrayList<String> str = new ArrayList<>(Arrays.asList(super.getInfoData()));
        str.add("tt.infodata.eoh.control_block_statistics.header");
        if (spacetimeCompressionFieldMetadata < 0) {
            str.add("tt.infodata.eoh.spacetime_compression.grade.none");
        } else {
            str.add(
                IGregTechDeviceInformation.encode(
                    "tt.infodata.eoh.spacetime_compression.grade",
                    CommonValues.getLocalizedEohTierFancyNames(spacetimeCompressionFieldMetadata) + RESET,
                    "" + YELLOW + (spacetimeCompressionFieldMetadata + 1) + RESET));
        }
        if (timeAccelerationFieldMetadata < 0) {
            str.add("tt.infodata.eoh.time_dilation.grade.none");
        } else {
            str.add(
                IGregTechDeviceInformation.encode(
                    "tt.infodata.eoh.time_dilation.grade",
                    CommonValues.getLocalizedEohTierFancyNames(timeAccelerationFieldMetadata) + RESET,
                    "" + YELLOW + (timeAccelerationFieldMetadata + 1) + RESET));
        }
        if (stabilisationFieldMetadata < 0) {
            str.add("tt.infodata.eoh.stabilisation.grade.none");
        } else {
            str.add(
                IGregTechDeviceInformation.encode(
                    "tt.infodata.eoh.stabilisation.grade",
                    CommonValues.getLocalizedEohTierFancyNames(stabilisationFieldMetadata) + RESET,
                    "" + YELLOW + (stabilisationFieldMetadata + 1) + RESET));
        }
        str.add("tt.infodata.eoh.internal_storage.header");
        validFluidMap.forEach(
            (key, value) -> str.add(BLUE + key.getLocalizedName() + RESET + " : " + RED + formatNumber(value)));
        str.add(
            IGregTechDeviceInformation
                .encode("tt.infodata.eoh.astral_array_fabricators.count", formatNumber(astralArrayAmount)));
        if (recipeRunning) {
            str.add("tt.infodata.eoh.other_stats.header");
            str.add(
                IGregTechDeviceInformation
                    .encode("tt.infodata.eoh.success_chance", RED + formatNumber(100 * successChance) + RESET + "%"));
            str.add(
                IGregTechDeviceInformation
                    .encode("tt.infodata.eoh.recipe_yield", RED + formatNumber(100 * yield) + RESET + "%"));
            str.add(
                IGregTechDeviceInformation.encode(
                    "tt.infodata.eoh.effective_astral_array_fabricators",
                    RED + formatNumber(Math.min(astralArrayAmount, ASTRAL_ARRAY_LIMIT))));
            str.add(
                IGregTechDeviceInformation
                    .encode("tt.infodata.eoh.total_parallel", RED + formatNumber(parallelAmount)));
            str.add(
                IGregTechDeviceInformation
                    .encode("tt.infodata.eoh.eu_output", RED + toStandardForm(outputEU_BigInt) + RESET));
            str.add(
                IGregTechDeviceInformation
                    .encode("tt.infodata.eoh.eu_input", RED + toStandardForm(usedEU.abs()) + RESET));
            int currentMaxProgresstime = Math.max(maxProgresstime(), 1);
            if (starMatter != null && starMatter.fluidStack != null) {
                FluidStackLong starMatterOutput = new FluidStackLong(
                    starMatter.fluidStack,
                    (long) (starMatter.amount * yield * successChance * parallelAmount));
                str.add(
                    IGregTechDeviceInformation.encode(
                        "tt.infodata.eoh.avg_output",
                        starMatterOutput.fluidStack.getLocalizedName(),
                        RED + formatNumber(starMatterOutput.amount) + RESET,
                        YELLOW + formatNumber(starMatterOutput.amount * 20.0 / currentMaxProgresstime) + RESET));

                FluidStackLong stellarPlasmaOutput = new FluidStackLong(
                    Materials.RawStarMatter.getFluid(0),
                    (long) (stellarPlasma.amount * yield * successChance * parallelAmount));
                str.add(
                    IGregTechDeviceInformation.encode(
                        "tt.infodata.eoh.avg_output",
                        stellarPlasmaOutput.fluidStack.getLocalizedName(),
                        RED + formatNumber(stellarPlasmaOutput.amount) + RESET,
                        YELLOW + formatNumber(stellarPlasmaOutput.amount * 20.0 / currentMaxProgresstime) + RESET));
            }
            BigInteger euPerTick = (outputEU_BigInt.subtract(usedEU.abs()))
                .divide(BigInteger.valueOf(currentMaxProgresstime));

            str.add(
                IGregTechDeviceInformation
                    .encode("tt.infodata.eoh.estimated_eu", RED + toStandardForm(euPerTick) + RESET));
        }
        str.add("tt.infodata.eoh.divider");
        return str.toArray(new String[0]);
    }

    @Override
    public String[] getStructureDescription(ItemStack stackSize) {
        return new String[] { "Eye of Harmony multiblock" };
    }

    // NBT save/load strings.
    private static final String EYE_OF_HARMONY = "eyeOfHarmonyOutput";
    private static final String NUMBER_OF_ITEMS_NBT_TAG = EYE_OF_HARMONY + "numberOfItems";
    private static final String NUMBER_OF_FLUIDS_NBT_TAG = EYE_OF_HARMONY + "numberOfFluids";
    private static final String ITEM_OUTPUT_NBT_TAG = EYE_OF_HARMONY + "itemOutput";
    private static final String FLUID_OUTPUT_NBT_TAG = EYE_OF_HARMONY + "fluidOutput";
    private static final String RECIPE_RUNNING_NBT_TAG = EYE_OF_HARMONY + "recipeRunning";
    private static final String CURRENT_RECIPE_STAR_MATTER_TAG = EYE_OF_HARMONY + "recipeStarMatter";
    private static final String CURRENT_RECIPE_STELLAR_PLASMA_TAG = EYE_OF_HARMONY + "recipeStellarPlasma";
    private static final String CURRENT_RECIPE_FIXED_OUTPUTS_TAG = EYE_OF_HARMONY + "recipeFixedOutputs";
    private static final String RECIPE_SUCCESS_CHANCE_NBT_TAG = EYE_OF_HARMONY + "recipeSuccessChance";
    private static final String ROCKET_TIER_NBT_TAG = EYE_OF_HARMONY + "rocketTier";
    private static final String CURRENT_CIRCUIT_MULTIPLIER_TAG = EYE_OF_HARMONY + "currentCircuitMultiplier";
    private static final String ANIMATIONS_ENABLED = EYE_OF_HARMONY + "animationsEnabled";
    private static final String CALCULATED_EU_OUTPUT_NBT_TAG = EYE_OF_HARMONY + "outputEU_BigInt";
    private static final String PARALLEL_AMOUNT_NBT_TAG = EYE_OF_HARMONY + "parallelAmount";
    private static final String YIELD_NBT_TAG = EYE_OF_HARMONY + "yield";
    private static final String SUCCESSFUL_PARALLEL_AMOUNT_NBT_TAG = EYE_OF_HARMONY + "successfulParallelAmount";
    private static final String ASTRAL_ARRAY_AMOUNT_NBT_TAG = EYE_OF_HARMONY + "astralArrayAmount";
    private static final String CALCULATED_EU_INPUT_NBT_TAG = EYE_OF_HARMONY + "usedEU";
    private static final String EXTRA_PITY_CHANCE_BOOST_NBT_TAG = EYE_OF_HARMONY + "pityChance";
    private static final String PREVIOUS_RECIPE_CHANCE_NBT_TAG = EYE_OF_HARMONY + "previousChance";

    // Sub tags, less specific names required.
    private static final String STACK_SIZE = "stackSize";
    private static final String ITEM_STACK_NBT_TAG = "itemStack";
    private static final String FLUID_AMOUNT = "fluidAmount";
    private static final String FLUID_STACK_NBT_TAG = "fluidStack";

    // Tags for pre-setting
    public static final String PLANET_BLOCK = "planetBlock";

    @Override
    public void initDefaultModes(NBTTagCompound aNBT) {
        super.initDefaultModes(aNBT);
        if (aNBT != null && aNBT.hasKey(PLANET_BLOCK) && getControllerSlot() == null) {
            mInventory[getControllerSlotIndex()] = new ItemStack(ModBlocks.getBlock(aNBT.getString(PLANET_BLOCK)));
            aNBT.removeTag(PLANET_BLOCK);
        }
    }

    @Override
    public void addAdditionalTooltipInformation(ItemStack stack, List<String> tooltip) {
        if (stack.hasTagCompound()) {
            NBTTagCompound nbt = stack.getTagCompound();
            if (nbt.hasKey(PLANET_BLOCK)) {
                tooltip.add(
                    1,
                    StatCollector.translateToLocalFormatted(
                        "EOH_Controller_PlanetBlock",
                        AQUA + new ItemStack(ModBlocks.getBlock(nbt.getString(PLANET_BLOCK))).getDisplayName()));
            }
            if (nbt.getLong(ASTRAL_ARRAY_AMOUNT_NBT_TAG) > 0) {
                tooltip.add(
                    1,
                    StatCollector.translateToLocalFormatted(
                        "EOH_Controller_AstralArrayAmount",
                        AQUA + formatNumber(nbt.getLong(ASTRAL_ARRAY_AMOUNT_NBT_TAG))));
            }
        }
    }

    @Override
    public void setItemNBT(NBTTagCompound NBT) {
        if (astralArrayAmount > 0) NBT.setLong(ASTRAL_ARRAY_AMOUNT_NBT_TAG, astralArrayAmount);
    }

    @Override
    public void saveNBTData(NBTTagCompound aNBT) {
        // Save the quantity of fluid stored inside the controller.
        validFluidMap.forEach((key, value) -> aNBT.setLong("stored." + key.getUnlocalizedName(), value));

        aNBT.setBoolean(RECIPE_RUNNING_NBT_TAG, recipeRunning);
        aNBT.setDouble(RECIPE_SUCCESS_CHANCE_NBT_TAG, successChance);
        aNBT.setLong(ROCKET_TIER_NBT_TAG, currentRecipeRocketTier);
        aNBT.setLong(CURRENT_CIRCUIT_MULTIPLIER_TAG, currentCircuitMultiplier);
        aNBT.setBoolean(ANIMATIONS_ENABLED, animationsEnabled);
        aNBT.setLong(PARALLEL_AMOUNT_NBT_TAG, parallelAmount);
        aNBT.setLong(SUCCESSFUL_PARALLEL_AMOUNT_NBT_TAG, successfulParallelAmount);
        aNBT.setDouble(YIELD_NBT_TAG, yield);
        aNBT.setLong(ASTRAL_ARRAY_AMOUNT_NBT_TAG, astralArrayAmount);
        aNBT.setByteArray(CALCULATED_EU_OUTPUT_NBT_TAG, outputEU_BigInt.toByteArray());
        aNBT.setByteArray(CALCULATED_EU_INPUT_NBT_TAG, usedEU.toByteArray());
        aNBT.setDouble(EXTRA_PITY_CHANCE_BOOST_NBT_TAG, pityChance);
        aNBT.setDouble(PREVIOUS_RECIPE_CHANCE_NBT_TAG, previousRecipeChance);

        // Store damage values/stack sizes of GT items being outputted.
        NBTTagCompound itemStackListNBTTag = new NBTTagCompound();
        itemStackListNBTTag.setLong(NUMBER_OF_ITEMS_NBT_TAG, outputItems.size());

        int index = 0;
        for (ItemStackLong itemStackLong : outputItems) {
            // Save stack size to NBT.
            itemStackListNBTTag.setLong(index + STACK_SIZE, itemStackLong.stackSize);

            // Save ItemStack to NBT.
            aNBT.setTag(index + ITEM_STACK_NBT_TAG, itemStackLong.itemStack.writeToNBT(new NBTTagCompound()));

            index++;
        }

        aNBT.setTag(ITEM_OUTPUT_NBT_TAG, itemStackListNBTTag);

        // Store damage values/stack sizes of GT fluids being outputted.
        NBTTagCompound fluidStackListNBTTag = new NBTTagCompound();
        fluidStackListNBTTag.setLong(NUMBER_OF_FLUIDS_NBT_TAG, outputFluids.size());

        int indexFluids = 0;
        for (FluidStackLong fluidStackLong : outputFluids) {
            // Save fluid amount to NBT.
            fluidStackListNBTTag.setLong(indexFluids + FLUID_AMOUNT, fluidStackLong.amount);

            // Save FluidStack to NBT.
            aNBT.setTag(indexFluids + FLUID_STACK_NBT_TAG, fluidStackLong.fluidStack.writeToNBT(new NBTTagCompound()));

            indexFluids++;
        }

        aNBT.setTag(FLUID_OUTPUT_NBT_TAG, fluidStackListNBTTag);

        if (starMatter != null && starMatter.fluidStack != null) {

            NBTTagCompound fixedRecipeOutputs = new NBTTagCompound();

            fixedRecipeOutputs.setLong(0 + FLUID_AMOUNT, starMatter.amount);
            aNBT.setTag(CURRENT_RECIPE_STAR_MATTER_TAG, starMatter.fluidStack.writeToNBT(new NBTTagCompound()));

            fixedRecipeOutputs.setLong(1 + FLUID_AMOUNT, stellarPlasma.amount);
            aNBT.setTag(CURRENT_RECIPE_STELLAR_PLASMA_TAG, stellarPlasma.fluidStack.writeToNBT(new NBTTagCompound()));

            aNBT.setTag(CURRENT_RECIPE_FIXED_OUTPUTS_TAG, fixedRecipeOutputs);
        }

        super.saveNBTData(aNBT);
    }

    @Override
    public void loadNBTData(final NBTTagCompound aNBT) {

        // Load the quantity of fluid stored inside the controller.
        validFluidMap
            .forEach((key, value) -> validFluidMap.put(key, aNBT.getLong("stored." + key.getUnlocalizedName())));

        // Load other stuff from NBT.
        recipeRunning = aNBT.getBoolean(RECIPE_RUNNING_NBT_TAG);
        successChance = aNBT.getDouble(RECIPE_SUCCESS_CHANCE_NBT_TAG);
        currentRecipeRocketTier = aNBT.getLong(ROCKET_TIER_NBT_TAG);
        currentCircuitMultiplier = aNBT.getLong(CURRENT_CIRCUIT_MULTIPLIER_TAG);
        if (aNBT.hasKey(ANIMATIONS_ENABLED)) animationsEnabled = aNBT.getBoolean(ANIMATIONS_ENABLED);
        parallelAmount = aNBT.getLong(PARALLEL_AMOUNT_NBT_TAG);
        yield = aNBT.getDouble(YIELD_NBT_TAG);
        successfulParallelAmount = aNBT.getLong(SUCCESSFUL_PARALLEL_AMOUNT_NBT_TAG);
        astralArrayAmount = aNBT.getLong(ASTRAL_ARRAY_AMOUNT_NBT_TAG);
        if (aNBT.hasKey(CALCULATED_EU_OUTPUT_NBT_TAG))
            outputEU_BigInt = new BigInteger(aNBT.getByteArray(CALCULATED_EU_OUTPUT_NBT_TAG));
        if (aNBT.hasKey(CALCULATED_EU_INPUT_NBT_TAG))
            usedEU = new BigInteger(aNBT.getByteArray(CALCULATED_EU_INPUT_NBT_TAG));
        pityChance = aNBT.getDouble(EXTRA_PITY_CHANCE_BOOST_NBT_TAG);
        previousRecipeChance = aNBT.getDouble(PREVIOUS_RECIPE_CHANCE_NBT_TAG);

        // Load damage values/stack sizes of GT items being outputted and convert back to items.
        NBTTagCompound tempItemTag = aNBT.getCompoundTag(ITEM_OUTPUT_NBT_TAG);

        // Iterate over all stored items.
        for (int index = 0; index < tempItemTag.getInteger(NUMBER_OF_ITEMS_NBT_TAG); index++) {

            // Load stack size from NBT.
            long stackSize = tempItemTag.getLong(index + STACK_SIZE);

            // Load ItemStack from NBT.
            ItemStack itemStack = ItemStack.loadItemStackFromNBT(aNBT.getCompoundTag(index + ITEM_STACK_NBT_TAG));

            outputItems.add(new ItemStackLong(itemStack, stackSize));
        }

        // Load damage values/fluid amounts of GT fluids being outputted and convert back to fluids.
        NBTTagCompound tempFluidTag = aNBT.getCompoundTag(FLUID_OUTPUT_NBT_TAG);

        // Iterate over all stored fluids.
        for (int indexFluids = 0; indexFluids < tempFluidTag.getInteger(NUMBER_OF_FLUIDS_NBT_TAG); indexFluids++) {

            // Load fluid amount from NBT.
            long fluidAmount = tempFluidTag.getLong(indexFluids + FLUID_AMOUNT);

            // Load FluidStack from NBT.
            FluidStack fluidStack = FluidStack
                .loadFluidStackFromNBT(aNBT.getCompoundTag(indexFluids + FLUID_STACK_NBT_TAG));

            outputFluids.add(new FluidStackLong(fluidStack, fluidAmount));
        }

        tempFluidTag = aNBT.getCompoundTag(CURRENT_RECIPE_FIXED_OUTPUTS_TAG);
        starMatter = new FluidStackLong(
            FluidStack.loadFluidStackFromNBT(aNBT.getCompoundTag(CURRENT_RECIPE_STAR_MATTER_TAG)),
            tempFluidTag.getLong(0 + FLUID_AMOUNT));
        stellarPlasma = new FluidStackLong(
            FluidStack.loadFluidStackFromNBT(aNBT.getCompoundTag(CURRENT_RECIPE_STELLAR_PLASMA_TAG)),
            tempFluidTag.getLong(1 + FLUID_AMOUNT));

        super.loadNBTData(aNBT);
    }

    @Override
    public boolean supportsSingleRecipeLocking() {
        return false;
    }

    @Override
    public boolean getDefaultHasMaintenanceChecks() {
        return false;
    }

    @SideOnly(Side.CLIENT)
    @Override
    protected SoundResource getActivitySoundLoop() {
        return SoundResource.GT_MACHINES_EYE_OF_HARMONY_LOOP;
    }
}
