package gregtech.api.logic;

import static gregtech.api.logic.TestRecipes.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.math.BigInteger;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import net.minecraft.util.EnumChatFormatting;

import org.junit.jupiter.api.Test;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.VoltageIndex;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.OverclockCalculator;

class ProcessingSpecTest {

    private static final List<MachineMode> TOWER_AND_DISTILLERY = List.of(
        MachineMode.of(mock(RecipeMap.class))
            .nameKey("Tower"),
        MachineMode.of(mock(RecipeMap.class))
            .nameKey("Distillery"));

    private static final ModifierKind<Integer> MOMENTUM = ModifierKind.ofInt("test:momentum")
        .source(ModifierKind.Source.RUNTIME)
        .ordered()
        .register();
    private static final ModifierKind<Integer> CASING = ModifierKind.ofInt("test:casing")
        .labels(0, "Heat Resistant Casing", "Heat Proof Casing")
        .register();

    private static ProcessingSpec.Inputs inputs(int voltageTier, int coilTier) {
        return ProcessingSpec.Inputs.builder()
            .energyHatches(voltageTier, 1)
            .value(ModifierKind.COIL, coilTier)
            .build();
    }

    private static ProcessingSpec.Inputs inMode(int mode) {
        return ProcessingSpec.Inputs.builder()
            .energyHatches(VoltageIndex.LV, 1)
            .mode(mode)
            .build();
    }

    private static List<String> lines(ProcessingSpec spec) {
        MultiblockTooltipBuilder tt = new MultiblockTooltipBuilder();
        spec.describe(tt);
        return tt.getInfoLines();
    }

    @Test
    void inputsNameEveryValue() {
        ProcessingSpec.Inputs luv = ProcessingSpec.Inputs.builder()
            .energyHatches(VoltageIndex.LuV, 1)
            .value(ModifierKind.SOLENOID, VoltageIndex.LuV)
            .build();

        assertEquals(VoltageIndex.LuV, luv.value(ModifierKind.VOLTAGE));
        assertEquals(VoltageIndex.LuV, luv.value(ModifierKind.SOLENOID));
        assertEquals(2, luv.amperage());
        assertTrue(luv.isSingleRegularHatch());
        assertEquals(0, luv.mode());
        assertThrows(IllegalArgumentException.class, () -> luv.value(ModifierKind.COIL));
    }

    @Test
    void parallelTermsAddUpAndNeverFallBelowOne() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallelPerTier(4, ModifierKind.VOLTAGE)
            .parallel(3)
            .build();

        assertEquals(4 * 5 + 3, spec.getMaxParallel(inputs(5, 0)));
        assertEquals(1, ProcessingSpec.STANDARD.getMaxParallel(inputs(5, 3)));
    }

    @Test
    void parallelPerTierMultipliesItsTiers() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallelPerTier(6, ModifierKind.VOLTAGE, ModifierKind.SOLENOID)
            .build();
        ProcessingSpec.Inputs luv = ProcessingSpec.Inputs.builder()
            .energyHatches(VoltageIndex.LuV, 1)
            .value(ModifierKind.SOLENOID, VoltageIndex.LuV)
            .build();

        assertEquals(6 * 6 * 6, spec.getMaxParallel(luv));
    }

    @Test
    void coilTermsCountFromOne() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .speedPerTier(0, 0.5, ModifierKind.COIL)
            .euDiscountPerTier(0.1, ModifierKind.COIL)
            .maxEuDiscount(0.5)
            .build();

        // Cupronickel is coil tier 0 and tier 1 on the tooltip
        assertEquals(1 / 0.5, spec.getDurationMultiplier(inputs(1, 0)));
        assertEquals(0.9, spec.getEuModifier(inputs(1, 0)), 1e-12);
        assertEquals(0.5, spec.getEuModifier(inputs(1, 9)));
    }

    @Test
    void speedPerTierAddsItsBase() {
        ProcessingSpec withBase = ProcessingSpec.builder()
            .speedPerTier(1, 1, ModifierKind.ITEM_PIPE_CASING)
            .build();
        ProcessingSpec withoutBase = ProcessingSpec.builder()
            .speedPerTier(0, 0.5, ModifierKind.ITEM_PIPE_CASING)
            .build();
        ProcessingSpec.Inputs lowestCasing = ProcessingSpec.Inputs.builder()
            .value(ModifierKind.ITEM_PIPE_CASING, 1)
            .build();

        assertEquals(1 / 2.0, withBase.getDurationMultiplier(lowestCasing));
        assertEquals(1 / 0.5, withoutBase.getDurationMultiplier(lowestCasing));
        assertEquals(1, lines(withBase).size());
        assertEquals(1, lines(withoutBase).size());
    }

    @Test
    void speedIsTheReciprocalOfTheTooltipSpeed() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .speed(2.5)
            .build();

        assertEquals(0.4, spec.getDurationMultiplier(inputs(1, 0)));
    }

    @Test
    void applySpecSetsOnlyWhatTheSpecDeclares() {
        ProcessingLogic logic = new ProcessingLogic().setSpeedBonus(0.5)
            .setMaxParallel(7)
            .setOverclock(3, 4)
            .applySpec(
                ProcessingSpec.builder()
                    .euModifier(0.8)
                    .unlimitedTierSkips()
                    .build(),
                inputs(5, 3));

        OverclockCalculator calculator = logic.createOverclockCalculatorForInspection(recipe(30, 200, 0));

        assertEquals(7, logic.getResolvedMaxParallel());
        assertEquals(0.5, calculator.getDurationModifier());
        assertEquals(0.8, calculator.getEUtDiscount());
        assertEquals(3, calculator.getDurationDecreasePerOC());
        assertEquals(Integer.MAX_VALUE, calculator.getMaxTierSkips());
        assertFalse(calculator.isHeatOC());
    }

    @Test
    void applySpecCarriesHeatIntoTheCalculator() {
        ProcessingLogic logic = new ProcessingLogic().applySpec(
            ProcessingSpec.builder()
                .parallel(4)
                .heat(
                    in -> 1000 * in.value(ModifierKind.COIL),
                    ProcessingSpec.HeatRule.OVERCLOCK,
                    ProcessingSpec.HeatRule.DISCOUNT)
                .build(),
            inputs(5, 3));

        OverclockCalculator calculator = logic.createOverclockCalculatorForInspection(recipe(30, 200, 1800));

        assertEquals(4, logic.getResolvedMaxParallel());
        assertEquals(3000, calculator.getMachineHeat());
        assertEquals(1800, calculator.getRecipeHeat());
        assertTrue(calculator.isHeatOC());
        assertTrue(calculator.isHeatDiscount());
    }

    @Test
    void aFixedRecipeHeatReplacesTheRecipes() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .heat(in -> 3600, ProcessingSpec.HeatRule.OVERCLOCK)
            .recipeHeat(0)
            .build();

        assertEquals(
            0,
            spec.getHeat()
                .get()
                .getRecipeHeat(recipe(30, 200, 1800)));
    }

    @Test
    void aRuntimeValueIsReadAtEveryRecipeCheck() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallel(in -> 4 + in.value(MOMENTUM))
            .noTooltip(ProcessingSpec.Quantity.PARALLEL)
            .build();
        ProcessingLogic logic = new ProcessingLogic().applySpec(
            spec,
            ProcessingSpec.Inputs.builder()
                .value(MOMENTUM, 0)
                .build());

        logic.createOverclockCalculatorForInspection(recipe(30, 200, 0));
        assertEquals(4, logic.getResolvedMaxParallel());

        logic.applySpec(
            spec,
            ProcessingSpec.Inputs.builder()
                .value(MOMENTUM, 100)
                .build());
        logic.createOverclockCalculatorForInspection(recipe(30, 200, 0));
        assertEquals(104, logic.getResolvedMaxParallel());
    }

    /** As the steam multiblocks run. */
    @Test
    void noOverclockRunsAtTheRecipesOwnVoltageWithItsCost() {
        ProcessingLogic logic = new ProcessingLogic().setAvailableVoltage(32)
            .applySpec(
                ProcessingSpec.builder()
                    .durationMultiplier(in -> 0.8)
                    .euModifierNotLimitingParallel(in -> 2.5)
                    .noOverclock()
                    .build(),
                inputs(1, 0));
        GTRecipe recipe = recipe(16, 200, 0);

        OverclockCalculator planned = logic.createOverclockCalculatorForInspection(recipe)
            .setParallel(8)
            .calculate();

        assertTrue(planned.isNoOverclock());
        assertEquals(16, planned.getMachineVoltage());
        assertEquals(160, planned.getDuration());
        assertEquals(16 * 2.5 * 8, planned.getConsumption());
    }

    @Test
    void theTooltipAccountsForWhatTheSpecSets() {
        ProcessingSpec described = ProcessingSpec.builder()
            .parallelPerTier(4, ModifierKind.VOLTAGE)
            .speed(2)
            .perfectOverclock()
            .build();
        ProcessingSpec undescribed = ProcessingSpec.builder()
            .parallel(in -> 4)
            .euModifier(in -> 0.9)
            .build();
        ProcessingSpec overridden = ProcessingSpec.builder()
            .parallel(in -> 4)
            .customTooltip(ProcessingSpec.Quantity.PARALLEL, tt -> tt.addInfo("Four at once"))
            .euModifier(in -> 0.9)
            .noTooltip(ProcessingSpec.Quantity.EU_MODIFIER)
            .build();

        assertTrue(
            described.getUndescribed()
                .isEmpty());
        assertEquals(
            EnumSet.of(ProcessingSpec.Quantity.PARALLEL, ProcessingSpec.Quantity.EU_MODIFIER),
            undescribed.getUndescribed());
        assertTrue(
            overridden.getUndescribed()
                .isEmpty());
        assertEquals(
            EnumSet.of(ProcessingSpec.Quantity.PARALLEL, ProcessingSpec.Quantity.EU_MODIFIER),
            overridden.describe(new MultiblockTooltipBuilder()));
    }

    /** An LV recipe on an LuV Industrial Forge Hammer with one energy hatch, for each solenoid. */
    @Test
    void calculateFollowsTheParallelAndOverclockLimits() {
        ProcessingSpec forgeHammer = ProcessingSpec.builder()
            .parallelPerTier(6, ModifierKind.VOLTAGE, ModifierKind.SOLENOID)
            .speed(2)
            .euModifier(1)
            .build();
        GTRecipe ironPlates = recipe(16, 56, 0);
        int[][] expected = {
            // solenoid, parallel, overclocks, ticks, EU/t
            { VoltageIndex.MV, 72, 2, 7, 18432 }, { VoltageIndex.HV, 108, 2, 7, 27648 },
            { VoltageIndex.EV, 144, 1, 14, 9216 }, { VoltageIndex.IV, 180, 1, 14, 11520 },
            { VoltageIndex.LuV, 216, 1, 14, 13824 }, { VoltageIndex.UMV, 432, 1, 14, 27648 } };

        for (int[] row : expected) {
            ProcessingSpec.Run run = forgeHammer.calculate(
                ironPlates,
                ProcessingSpec.Inputs.builder()
                    .energyHatches(VoltageIndex.LuV, 1)
                    .value(ModifierKind.SOLENOID, row[0])
                    .build());

            assertEquals(row[1], run.parallel(), "parallel at solenoid " + row[0]);
            assertEquals(row[2], run.overclocks(), "overclocks at solenoid " + row[0]);
            assertEquals(row[3], run.ticks(), "ticks at solenoid " + row[0]);
            assertEquals(row[4], run.euPerTick(), "EU/t at solenoid " + row[0]);
        }
    }

    @Test
    void calculateLimitsParallelsToTheEnergyAvailable() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallel(256)
            .build();

        ProcessingSpec.Run run = spec.calculate(
            recipe(480, 100, 0),
            ProcessingSpec.Inputs.builder()
                .energyHatches(VoltageIndex.LuV, 1)
                .build());

        assertEquals(32768 / 480, run.parallel());
        assertEquals(0, run.overclocks());
    }

    @Test
    void inModeReplacesTheSpecsOwnTermsInThatMode() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .modes(TOWER_AND_DISTILLERY)
            .parallel(4)
            .inMode(
                1,
                mode -> mode.parallel(8)
                    .speed(2))
            .build();

        assertEquals(4, spec.getMaxParallel(inMode(0)));
        assertEquals(1, spec.getDurationMultiplier(inMode(0)));
        assertEquals(8, spec.getMaxParallel(inMode(1)));
        assertEquals(0.5, spec.getDurationMultiplier(inMode(1)));
        assertTrue(
            spec.getUndescribed()
                .isEmpty());

        List<String> lines = lines(spec);
        String distillery = EnumChatFormatting.WHITE + "Distillery" + EnumChatFormatting.GRAY + ": ";
        assertEquals(3, lines.size(), "the spec's own line and the Distillery's two");
        assertFalse(
            lines.get(0)
                .startsWith(distillery));
        assertTrue(
            lines.get(1)
                .startsWith(distillery));
        assertTrue(
            lines.get(2)
                .startsWith(distillery));
    }

    @Test
    void inModeNeedsTheModeDeclared() {
        assertThrows(
            IllegalStateException.class,
            () -> ProcessingSpec.builder()
                .inMode(1, mode -> mode.parallel(8))
                .build());
        assertThrows(
            IllegalStateException.class,
            () -> ProcessingSpec.builder()
                .modes(TOWER_AND_DISTILLERY)
                .inMode(2, mode -> mode.parallel(8))
                .build());
    }

    @Test
    void requirementsDecideWhetherARecipeRuns() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .heat(in -> 1800, ProcessingSpec.HeatRule.REQUIRED)
            .noTooltip(ProcessingSpec.Quantity.HEAT)
            .requires((in, recipe) -> recipe.mEUt <= 30, (in, recipe) -> CheckRecipeResultRegistry.NO_RECIPE)
            .build();
        ProcessingSpec.Inputs ev = inputs(VoltageIndex.EV, 0);

        assertTrue(
            spec.check(recipe(30, 200, 1800), ev)
                .wasSuccessful());
        assertFalse(
            spec.check(recipe(30, 200, 2700), ev)
                .wasSuccessful(),
            "hotter than the machine");
        assertFalse(
            spec.check(recipe(120, 200, 0), ev)
                .wasSuccessful());
        ProcessingSpec.Run run = spec.calculate(recipe(120, 200, 0), ev);
        assertFalse(
            run.result()
                .wasSuccessful());
        assertEquals(0, run.ticks());
    }

    @Test
    void termsThatReadTheRecipeApplyOnceItIsKnown() {
        ProcessingSpec parallel = ProcessingSpec.builder()
            .parallel(2)
            .parallelPerRecipe((in, recipe) -> recipe.mDuration / 100)
            .noTooltip(ProcessingSpec.Quantity.PARALLEL)
            .build();
        ProcessingSpec.Inputs iv = inputs(VoltageIndex.IV, 0);

        assertEquals(2, parallel.getMaxParallel(iv), "a display without a recipe leaves them out");
        assertEquals(6, parallel.getMaxParallel(iv, recipe(30, 400, 0)));

        ProcessingSpec timing = ProcessingSpec.builder()
            .durationPerRecipe((in, recipe) -> 50)
            .noTooltip(ProcessingSpec.Quantity.DURATION)
            .maxOverclocksPerRecipe((in, recipe) -> 1)
            .noTooltip(ProcessingSpec.Quantity.OVERCLOCK)
            .build();
        ProcessingSpec.Run run = timing.calculate(recipe(30, 400, 0), iv);

        // 50 ticks instead of the recipe's 400, then one overclock where IV would allow four
        assertEquals(1, run.overclocks());
        assertEquals(25, run.ticks());
        assertEquals(120, run.euPerTick());
    }

    @Test
    void termsThatReadTheRecipeApplyInEveryMode() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ProcessingSpec.builder()
                .inMode(0, mode -> mode.parallelPerRecipe((in, recipe) -> 4)));
        assertThrows(
            IllegalArgumentException.class,
            () -> ProcessingSpec.builder()
                .inMode(0, mode -> mode.requires((in, recipe) -> true, (in, recipe) -> null)));
    }

    @Test
    void standardPowerUsesOneAmpOfALoneRegularHatch() {
        ProcessingSpec spec = ProcessingSpec.STANDARD;
        long luv = GTValues.V[VoltageIndex.LuV];

        assertEquals(new ProcessingSpec.Power(luv, 1, true, false), spec.getPower(inputs(VoltageIndex.LuV, 0)));
        assertEquals(
            new ProcessingSpec.Power(luv, 4, true, false),
            spec.getPower(
                ProcessingSpec.Inputs.builder()
                    .energyHatches(VoltageIndex.LuV, 2)
                    .build()),
            "with two regular hatches, all four amps are used");
        assertEquals(
            new ProcessingSpec.Power(luv, 16, true, false),
            spec.getPower(
                ProcessingSpec.Inputs.builder()
                    .energyHatch(ProcessingSpec.EnergyHatch.exotic(VoltageIndex.LuV, 16))
                    .build()));
        assertFalse(spec.sets(ProcessingSpec.Quantity.POWER));
    }

    @Test
    void powerRulesReadTheHatchesAsTheMachineDoes() {
        ProcessingSpec.Inputs oneHatch = inputs(VoltageIndex.LuV, 0);
        long luv = GTValues.V[VoltageIndex.LuV];

        ProcessingSpec atOneAmp = ProcessingSpec.builder()
            .powerAtOneAmp()
            .build();
        assertEquals(new ProcessingSpec.Power(2 * luv, 1, true, false), atOneAmp.getPower(oneHatch));
        assertTrue(atOneAmp.sets(ProcessingSpec.Quantity.POWER));

        ProcessingSpec allAmps = ProcessingSpec.builder()
            .allAmps()
            .build();
        assertEquals(new ProcessingSpec.Power(luv, 2, true, false), allAmps.getPower(oneHatch));

        ProcessingSpec fixed = ProcessingSpec.builder()
            .power(in -> 32, in -> 8)
            .noAmperageOverclock()
            .build();
        assertEquals(new ProcessingSpec.Power(32, 8, false, false), fixed.getPower(oneHatch));
    }

    @Test
    void theOverclockCanDifferByTier() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .whenTier(ModifierKind.COIL, 1, tier -> tier.overclock(1, 4))
            .noTooltip(ProcessingSpec.Quantity.OVERCLOCK)
            .build();
        GTRecipe recipe = recipe(30, 400, 0);

        assertEquals(
            Optional.of(ProcessingSpec.OverclockRule.Ratio.STANDARD),
            spec.getOverclock(inputs(VoltageIndex.HV, 0)));
        assertEquals(
            Optional.of(new ProcessingSpec.OverclockRule.Ratio(1, 4)),
            spec.getOverclock(inputs(VoltageIndex.HV, 1)));
        assertEquals(
            100,
            spec.calculate(recipe, inputs(VoltageIndex.HV, 0))
                .ticks());
        assertEquals(
            400,
            spec.calculate(recipe, inputs(VoltageIndex.HV, 1))
                .ticks(),
            "overclocks that do not speed up");
        assertThrows(
            IllegalArgumentException.class,
            () -> ProcessingSpec.builder()
                .whenTier(ModifierKind.COIL, 1, ProcessingSpec.Builder::noOverclock));
    }

    @Test
    void aRecipeEuMultiplierMakesTheRecipeAskForMore() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .modes(TOWER_AND_DISTILLERY)
            .inMode(1, mode -> mode.recipeEuMultiplier(16))
            .requires(
                (in, recipe) -> recipe.mEUt <= in.averageVoltage(),
                (in, recipe) -> CheckRecipeResultRegistry.NO_RECIPE)
            .noTooltip(ProcessingSpec.Quantity.RECIPE_EU_MULTIPLIER)
            .build();
        GTRecipe recipe = recipe(30, 400, 0);
        ProcessingSpec.Inputs ev = ProcessingSpec.Inputs.builder()
            .energyHatches(VoltageIndex.EV, 1)
            .mode(1)
            .build();

        ProcessingSpec.Run run = spec.calculate(recipe, ev);

        // 480 EU/t on EV: one overclock
        assertEquals(1, run.overclocks());
        assertEquals(1920, run.euPerTick());
        assertFalse(
            spec.calculate(recipe(129, 400, 0), ev)
                .result()
                .wasSuccessful(),
            "2064 EU/t is more than EV");
        assertEquals(30, recipe.mEUt, "the recipe itself is left alone");
    }

    @Test
    void unlimitedEnergyLimitsNeitherParallelsNorOverclocks() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallel(256)
            .maxOverclocksPerRecipe((in, recipe) -> 2)
            .unlimitedEnergy(in -> in.value(ModifierKind.COIL) == 1)
            .noTooltip(ProcessingSpec.Quantity.OVERCLOCK, ProcessingSpec.Quantity.POWER)
            .build();
        GTRecipe recipe = recipe(480, 400, 0);

        ProcessingSpec.Run limited = spec.calculate(recipe, inputs(VoltageIndex.LuV, 0));
        ProcessingSpec.Run unlimited = spec.calculate(recipe, inputs(VoltageIndex.LuV, 1));

        assertEquals(32768 / 480, limited.parallel());
        assertEquals(256, unlimited.parallel());
        assertEquals(2, unlimited.overclocks());
        assertEquals(
            GTValues.V[VoltageIndex.LuV],
            spec.getPower(inputs(VoltageIndex.LuV, 1))
                .voltage(),
            "recipes still see the hatch voltage");
    }

    @Test
    void aRequirementToStartIsLeftToTheMachineButPlannersCheckIt() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .requiresToStart(
                (in, recipe) -> in.totalEu() >= 10_000,
                (in, recipe) -> CheckRecipeResultRegistry.NO_RECIPE)
            .noTooltip(ProcessingSpec.Quantity.POWER)
            .build();
        GTRecipe recipe = recipe(30, 100, 0);
        ProcessingSpec.Inputs hv = inputs(VoltageIndex.HV, 0);

        assertTrue(
            spec.check(recipe, hv)
                .wasSuccessful());
        assertFalse(
            spec.checkToStart(recipe, hv)
                .wasSuccessful());
        assertFalse(
            spec.calculate(recipe, hv)
                .result()
                .wasSuccessful());
        assertTrue(
            spec.calculate(recipe, inputs(VoltageIndex.IV, 0))
                .result()
                .wasSuccessful());
    }

    @Test
    void anUnsupportedModeIsNotCalculated() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .unsupportedInMode(2)
            .build();

        assertTrue(spec.supportsMode(0));
        assertFalse(spec.supportsMode(2));
        assertTrue(
            spec.calculate(recipe(30, 100, 0), inMode(0))
                .result()
                .wasSuccessful());
        assertThrows(IllegalArgumentException.class, () -> spec.calculate(recipe(30, 100, 0), inMode(2)));
    }

    @Test
    void theRunCarriesEuPerRunAndItsOutputOdds() {
        ProcessingSpec plain = ProcessingSpec.STANDARD;
        ProcessingSpec spec = ProcessingSpec.builder()
            .euPerRunPerRecipe(
                (in, recipe) -> BigInteger.valueOf(recipe.mDuration)
                    .pow(3))
            .euGeneratedPerRecipe((in, recipe) -> BigInteger.TEN)
            .successChancePerRecipe((in, recipe) -> 0.25)
            .outputYieldPerRecipe((in, recipe) -> 0.5)
            .noTooltip(ProcessingSpec.Quantity.POWER, ProcessingSpec.Quantity.OUTPUT)
            .build();
        ProcessingSpec.Inputs hv = inputs(VoltageIndex.HV, 0);

        ProcessingSpec.Run defaults = plain.calculate(recipe(30, 100, 0), hv);
        ProcessingSpec.Run run = spec.calculate(recipe(30, 100_000, 0), hv);

        assertEquals(BigInteger.ZERO, defaults.euPerRun());
        assertEquals(BigInteger.ZERO, defaults.euGeneratedPerRun());
        assertEquals(1, defaults.successChance());
        assertEquals(1, defaults.outputYield());
        assertEquals(new BigInteger("1000000000000000"), run.euPerRun());
        assertEquals(BigInteger.TEN, run.euGeneratedPerRun());
        assertEquals(0.25, run.successChance());
        assertEquals(0.5, run.outputYield());
        assertTrue(
            spec.getUndescribed()
                .isEmpty());
    }

    @Test
    void aRunDrawsNoMoreThanTheCap() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallel(16)
            .maxEuPerTick(in -> 100)
            .noTooltip(ProcessingSpec.Quantity.POWER)
            .build();

        ProcessingSpec.Run run = spec.calculate(recipe(30, 100, 0), inputs(VoltageIndex.HV, 0));

        assertEquals(16, run.parallel());
        assertEquals(100, run.euPerTick(), "16 parallels at 30 EU/t would cost 480");
    }

    @Test
    void theVoltageTierReadsTheSummedHatchVoltage() {
        ProcessingSpec.Inputs twoLuv = ProcessingSpec.Inputs.builder()
            .energyHatches(VoltageIndex.LuV, 2)
            .build();
        ProcessingSpec.Inputs mixed = ProcessingSpec.Inputs.builder()
            .energyHatches(VoltageIndex.LuV, 1)
            .energyHatches(VoltageIndex.IV, 1)
            .build();

        assertEquals(VoltageIndex.ZPM, twoLuv.voltageTier());
        assertEquals((GTValues.V[VoltageIndex.LuV] + GTValues.V[VoltageIndex.IV]) / 2, mixed.averageVoltage());
        assertEquals(4 * GTValues.V[VoltageIndex.LuV], twoLuv.totalEu());
    }

    @Test
    void theRunCarriesTheStartupEu() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .startupEuPerRecipe((in, recipe) -> 1000L * recipe.mEUt)
            .requires((in, recipe) -> recipe.mEUt <= 30, (in, recipe) -> CheckRecipeResultRegistry.NO_RECIPE)
            .noTooltip(ProcessingSpec.Quantity.POWER)
            .build();
        ProcessingSpec.Inputs iv = inputs(VoltageIndex.IV, 0);

        assertEquals(
            30_000,
            spec.calculate(recipe(30, 100, 0), iv)
                .startupEu());
        assertEquals(
            0,
            spec.calculate(recipe(120, 100, 0), iv)
                .startupEu(),
            "a recipe that cannot run starts nothing");
    }

    @Test
    void aModeWrittenInItsOwnSectionIsNotWrittenAgain() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .modes(
                List.of(
                    MachineMode.of(mock(RecipeMap.class))
                        .nameKey("Tower"),
                    MachineMode.of(mock(RecipeMap.class))
                        .nameKey("Distillery")))
            .unlimitedTierSkips()
            .inMode(0, mode -> mode.parallel(4))
            .inMode(
                1,
                mode -> mode.parallel(8)
                    .speed(2))
            .build();
        MultiblockTooltipBuilder tt = new MultiblockTooltipBuilder();

        spec.describeMode(tt, 1);
        assertEquals(
            2,
            tt.getInfoLines()
                .size());
        assertFalse(
            tt.getInfoLines()
                .get(0)
                .contains("Distillery"),
            "a mode's own section needs no heading");

        spec.describe(tt);
        List<String> lines = tt.getInfoLines();
        assertEquals(4, lines.size(), "then the tier skips line and the Tower's line, but not the Distillery's again");
        assertTrue(
            lines.get(3)
                .startsWith(EnumChatFormatting.WHITE + "Tower" + EnumChatFormatting.GRAY + ": "));
        assertThrows(IllegalArgumentException.class, () -> spec.describeMode(new MultiblockTooltipBuilder(), 2));
    }

    @Test
    void whenTierAppliesAtThatValueAndIsHeadedByItsLabel() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .whenTier(
                CASING,
                0,
                tier -> tier.parallel(16)
                    .parallelPerTier(8, ModifierKind.LENGTH))
            .whenTier(
                CASING,
                1,
                tier -> tier.parallel(32)
                    .parallelPerTier(16, ModifierKind.LENGTH))
            .build();

        for (int structure = 0; structure <= 1; structure++) {
            ProcessingSpec.Inputs inputs = ProcessingSpec.Inputs.builder()
                .value(CASING, structure)
                .value(ModifierKind.LENGTH, 3)
                .build();
            assertEquals(structure == 0 ? 16 + 8 * 3 : 32 + 16 * 3, spec.getMaxParallel(inputs));
        }
        List<String> lines = lines(spec);
        assertEquals(4, lines.size());
        assertTrue(
            lines.get(3)
                .startsWith(EnumChatFormatting.WHITE + "Heat Proof Casing" + EnumChatFormatting.GRAY + ": "));
    }

    @Test
    void aTermThatDiffersByModeNeedsATooltipToo() {
        ProcessingSpec undescribed = ProcessingSpec.builder()
            .modes(TOWER_AND_DISTILLERY)
            .inMode(1, mode -> mode.parallel(in -> 3))
            .build();
        ProcessingSpec coveredForEveryMode = ProcessingSpec.builder()
            .modes(TOWER_AND_DISTILLERY)
            .inMode(1, mode -> mode.parallel(in -> 3))
            .noTooltip(ProcessingSpec.Quantity.PARALLEL)
            .build();

        assertEquals(EnumSet.of(ProcessingSpec.Quantity.PARALLEL), undescribed.getUndescribed());
        assertTrue(
            coveredForEveryMode.getUndescribed()
                .isEmpty());
        assertThrows(
            IllegalArgumentException.class,
            () -> ProcessingSpec.builder()
                .inMode(1, mode -> mode.maxTierSkips(2)));
    }

    /** As the Large Fluid Extractor: Cupronickel coils give the base speed and EU. */
    @Test
    void beyondFirstTermsStartAtTheFirstTier() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .speedPerTierBeyondFirst(1.5, 0.1, ModifierKind.COIL)
            .euModifierPerTierBeyondFirst(0.8, 0.9, ModifierKind.COIL)
            .build();

        assertEquals(1 / 1.5, spec.getDurationMultiplier(inputs(1, 0)), 1e-12);
        assertEquals(0.8, spec.getEuModifier(inputs(1, 0)), 1e-12);
        assertEquals(1 / 1.6, spec.getDurationMultiplier(inputs(1, 1)), 1e-12);
        assertEquals(0.72, spec.getEuModifier(inputs(1, 1)), 1e-12);
        assertEquals(1 / 2.8, spec.getDurationMultiplier(inputs(1, 13)), 1e-12);
        assertEquals(0.203349266266, spec.getEuModifier(inputs(1, 13)), 1e-12);
        assertTrue(
            spec.getUndescribed()
                .isEmpty());
    }
}
