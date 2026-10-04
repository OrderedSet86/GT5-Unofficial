package gregtech.api.logic;

import static gregtech.api.logic.TestRecipes.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.EnumSet;
import java.util.List;
import java.util.OptionalInt;

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

    private static final ModifierKind.IntKind MOMENTUM = ModifierKind.ofInt("test:momentum")
        .source(ModifierKind.Source.RUNTIME)
        .ordered()
        .range(0, 100)
        .register();
    private static final ModifierKind.IntKind SOLENOID = ModifierKind.ofInt("test:solenoid")
        .ordered()
        .range(VoltageIndex.MV, VoltageIndex.UMV)
        .register();
    private static final ModifierKind.IntKind CASING = ModifierKind.ofInt("test:casing")
        .range(0, 1)
        .labels(0, "Heat Resistant Casing", "Heat Proof Casing")
        .register();

    private static ProcessingInputs inputs(int voltageTier, int coilTier) {
        return ProcessingInputs.builder()
            .energyHatches(voltageTier, 1)
            .value(ModifierKind.COIL, coilTier)
            .build();
    }

    private static ProcessingInputs inMode(int mode) {
        return ProcessingInputs.builder()
            .energyHatches(VoltageIndex.LV, 1)
            .mode(mode)
            .build();
    }

    /** The common tooltip lines. */
    private static List<String> lines(ProcessingSpec spec) {
        MultiblockTooltipBuilder tt = new MultiblockTooltipBuilder();
        spec.describe(tt);
        return tt.getInfoLines();
    }

    @Test
    void inputsNameEveryValue() {
        ProcessingInputs luv = ProcessingInputs.builder()
            .energyHatches(VoltageIndex.LuV, 1)
            .value(SOLENOID, VoltageIndex.LuV)
            .build();

        assertEquals(VoltageIndex.LuV, luv.value(ModifierKind.VOLTAGE));
        assertEquals(VoltageIndex.LuV, luv.value(SOLENOID));
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
        assertEquals(
            1,
            ProcessingSpec.builder()
                .build()
                .getMaxParallel(inputs(5, 3)));
    }

    @Test
    void parallelPerTierMultipliesItsTiers() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallelPerTier(6, ModifierKind.VOLTAGE, SOLENOID)
            .build();
        ProcessingInputs luv = ProcessingInputs.builder()
            .energyHatches(VoltageIndex.LuV, 1)
            .value(SOLENOID, VoltageIndex.LuV)
            .build();

        assertEquals(6 * 6 * 6, spec.getMaxParallel(luv));
    }

    @Test
    void coilTermsCountFromOne() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .speedPerTier(0, 0.5, ModifierKind.COIL)
            .build();

        // Cupronickel is coil tier 0 and tier 1 on the tooltip
        assertEquals(1 / 0.5, spec.getDurationMultiplier(inputs(1, 0)));
    }

    @Test
    void speedPerTierAddsItsBase() {
        ProcessingSpec withBase = ProcessingSpec.builder()
            .speedPerTier(1, 1, ModifierKind.ITEM_PIPE_CASING)
            .build();
        ProcessingSpec withoutBase = ProcessingSpec.builder()
            .speedPerTier(0, 0.5, ModifierKind.ITEM_PIPE_CASING)
            .build();
        ProcessingInputs lowestCasing = ProcessingInputs.builder()
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
    void resolvingFillsWhatTheSpecLeavesUnsetWithTheDefaults() {
        ResolvedRecipe resolved = ProcessingSpec.builder()
            .euModifier(0.8)
            .unlimitedTierSkips()
            .build()
            .resolve(recipe(30, 200, 0), inputs(5, 3));

        assertEquals(1, resolved.maxParallel());
        assertEquals(1, resolved.durationMultiplier());
        assertEquals(0.8, resolved.euModifier());
        assertEquals(
            new ResolvedRecipe.Overclock(ProcessingSpec.OverclockRule.Ratio.STANDARD, Integer.MAX_VALUE, null),
            resolved.overclock());
        OverclockCalculator calculator = resolved.toCalculator();
        assertEquals(0.8, calculator.getEUtDiscount());
        assertEquals(2, calculator.getDurationDecreasePerOC());
        assertFalse(calculator.isHeatOC());
    }

    @Test
    void theCalculatorCarriesTheHeat() {
        ResolvedRecipe resolved = ProcessingSpec.builder()
            .parallel(4)
            .coilHeatPerVoltageTier(
                100,
                VoltageIndex.MV,
                ProcessingSpec.HeatRule.OVERCLOCK,
                ProcessingSpec.HeatRule.DISCOUNT)
            .build()
            .resolve(recipe(30, 200, 1800), inputs(VoltageIndex.IV, 3));

        OverclockCalculator calculator = resolved.toCalculator();

        assertEquals(4, resolved.maxParallel());
        // TPV coils, plus 100K for each of HV, EV and IV
        assertEquals(4501 + 300, calculator.getMachineHeat());
        assertEquals(1800, calculator.getRecipeHeat());
        assertTrue(calculator.isHeatOC());
        assertTrue(calculator.isHeatDiscount());
    }

    @Test
    void aRuntimeValueIsReadAtEveryRecipeCheck() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallel(in -> 4 + in.value(MOMENTUM), MOMENTUM)
            .noTooltip(ProcessingSpec.Quantity.PARALLEL)
            .build();
        ProcessingLogic logic = new ProcessingLogic().setSpec(spec, atMomentum(0));

        assertEquals(
            4,
            logic.inspect(recipe(1, 200, 0))
                .parallel());
        logic.setSpec(spec, atMomentum(100));
        assertEquals(
            104,
            logic.inspect(recipe(1, 200, 0))
                .parallel());
    }

    @Test
    void theInputsNeedEveryKindTheSpecReads() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallel(in -> 4 + in.value(MOMENTUM), MOMENTUM)
            .noTooltip(ProcessingSpec.Quantity.PARALLEL)
            .build();

        assertEquals(List.of(new ModifierRange(MOMENTUM, 0, 100)), spec.getModifiers());
        assertThrows(IllegalArgumentException.class, () -> spec.resolve(recipe(30, 200, 0), inputs(5, 3)));
        assertThrows(
            IllegalStateException.class,
            () -> ProcessingSpec.builder()
                .parallelPerTier(2, ModifierKind.LENGTH)
                .build(),
            "the length's range differs by machine, so the spec gives it");
    }

    @Test
    void theBestInputsPutOrderedKindsAtTheirMax() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallelPerVoltageTierRising(4, 8, MOMENTUM, 100)
            .whenTier(CASING, 0, tier -> tier.speed(1))
            .whenTier(CASING, 1, tier -> tier.speed(2))
            .build();

        ProcessingInputs best = spec.bestInputs()
            .energyHatches(VoltageIndex.EV, 1)
            .build();

        assertEquals(100, best.value(MOMENTUM));
        assertEquals(0, best.value(CASING), "an unordered kind has no best, so it starts at its min");
        assertEquals(32, spec.getMaxParallel(best));
    }

    /** As a planner picks the coil for an EBF recipe. */
    @Test
    void theLowestPassingValueMeetsTheRequirements() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .coilHeatPerVoltageTier(100, VoltageIndex.MV, ProcessingSpec.HeatRule.REQUIRED)
            .build();
        ProcessingInputs mv = spec.bestInputs()
            .energyHatches(VoltageIndex.MV, 1)
            .build();

        assertEquals(OptionalInt.of(0), spec.lowestPassing(ModifierKind.COIL, recipe(120, 100, 1700), mv));
        assertEquals(OptionalInt.of(2), spec.lowestPassing(ModifierKind.COIL, recipe(120, 100, 3600), mv));
        assertEquals(OptionalInt.empty(), spec.lowestPassing(ModifierKind.COIL, recipe(120, 100, 99_999), mv));
    }

    @Test
    void aSpecWithModeTermsVariesByMode() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .modes(TOWER_AND_DISTILLERY)
            .parallel(16)
            .inMode(1, mode -> mode.parallel(4))
            .build();

        assertTrue(spec.variesByMode());
        assertFalse(
            ProcessingSpec.builder()
                .build()
                .variesByMode());
    }

    /** As the steam multiblocks run. */
    @Test
    void noOverclockRunsAtTheRecipesOwnVoltageWithItsCost() {
        OverclockCalculator planned = ProcessingSpec.builder()
            .speed(in -> 1.25)
            .euModifierNotLimitingParallel(in -> 2.5)
            .noOverclock()
            .build()
            .resolve(recipe(16, 200, 0), inputs(1, 0))
            .toCalculator()
            .setParallel(8)
            .calculate();

        assertTrue(planned.isNoOverclock());
        assertEquals(160, planned.getDuration());
        assertEquals(16 * 2.5 * 8, planned.getConsumption());
    }

    /** As PlanNH's manual rows: a planner changes one calculator setting and keeps the rest of the spec. */
    @Test
    void aPlannerCanChangeOneCalculatorSetting() {
        ResolvedRecipe resolved = ProcessingSpec.builder()
            .parallel(4)
            .build()
            .resolve(recipe(30, 200, 0), inputs(VoltageIndex.EV, 0));

        ProcessingRun capped = resolved.calculate(
            resolved.toCalculator()
                .setMaxOverclocks(1));

        assertEquals(
            2,
            resolved.calculate()
                .overclocks());
        assertEquals(1, capped.overclocks());
        assertEquals(4, capped.parallel());
    }

    @Test
    void theTooltipAccountsForWhatTheSpecSets() {
        ProcessingSpec described = ProcessingSpec.builder()
            .parallelPerTier(4, ModifierKind.VOLTAGE)
            .speed(2)
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
        assertEquals(List.of("Four at once"), lines(overridden));
    }

    /** An LV recipe on an LuV Industrial Forge Hammer with one energy hatch, for each solenoid. */
    @Test
    void calculateFollowsTheParallelAndOverclockLimits() {
        ProcessingSpec forgeHammer = ProcessingSpec.builder()
            .parallelPerTier(6, ModifierKind.VOLTAGE, SOLENOID)
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
            ProcessingRun run = forgeHammer.calculate(
                ironPlates,
                ProcessingInputs.builder()
                    .energyHatches(VoltageIndex.LuV, 1)
                    .value(SOLENOID, row[0])
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

        ProcessingRun run = spec.calculate(
            recipe(480, 100, 0),
            ProcessingInputs.builder()
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

        assertEquals(1, lines(spec).size(), "the spec's own line: a mode's lines go in its own section");
        MultiblockTooltipBuilder distillery = new MultiblockTooltipBuilder();
        spec.describeMode(distillery, 1);
        assertEquals(
            2,
            distillery.getInfoLines()
                .size());
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
            // Cupronickel coils: 1801K
            .coilHeatPerVoltageTier(0, VoltageIndex.EV, ProcessingSpec.HeatRule.REQUIRED)
            .requires((in, recipe) -> recipe.mEUt <= 30, (in, recipe) -> CheckRecipeResultRegistry.NO_RECIPE)
            .build();
        ProcessingInputs ev = inputs(VoltageIndex.EV, 0);

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
        ProcessingRun run = spec.calculate(recipe(120, 200, 0), ev);
        assertFalse(
            run.result()
                .wasSuccessful());
        assertEquals(0, run.ticks());
    }

    @Test
    void termsThatReadTheRecipeApplyInEveryMode() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ProcessingSpec.builder()
                .inMode(0, mode -> mode.requires((in, recipe) -> true, (in, recipe) -> null)));
    }

    @Test
    void standardPowerUsesOneAmpOfALoneRegularHatch() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .build();
        long luv = GTValues.V[VoltageIndex.LuV];

        assertEquals(new ProcessingSpec.Power(luv, 1, true), spec.getPower(inputs(VoltageIndex.LuV, 0)));
        assertEquals(
            new ProcessingSpec.Power(luv, 4, true),
            spec.getPower(
                ProcessingInputs.builder()
                    .energyHatches(VoltageIndex.LuV, 2)
                    .build()),
            "with two regular hatches, all four amps are used");
        assertEquals(
            new ProcessingSpec.Power(luv, 16, true),
            spec.getPower(
                ProcessingInputs.builder()
                    .energyHatch(ProcessingInputs.EnergyHatch.exotic(VoltageIndex.LuV, 16))
                    .build()));
        assertFalse(spec.sets(ProcessingSpec.Quantity.POWER));
    }

    @Test
    void powerRulesReadTheHatchesAsTheMachineDoes() {
        ProcessingInputs oneHatch = inputs(VoltageIndex.LuV, 0);
        long luv = GTValues.V[VoltageIndex.LuV];

        ProcessingSpec atOneAmp = ProcessingSpec.builder()
            .powerAtOneAmp()
            .build();
        assertEquals(new ProcessingSpec.Power(2 * luv, 1, true), atOneAmp.getPower(oneHatch));
        assertTrue(atOneAmp.sets(ProcessingSpec.Quantity.POWER));

        ProcessingSpec fixed = ProcessingSpec.builder()
            .power(in -> 32, in -> 8)
            .noAmperageOverclock()
            .build();
        assertEquals(new ProcessingSpec.Power(32, 8, false), fixed.getPower(oneHatch));
    }

    @Test
    void aRunDrawsNoMoreThanTheCap() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallel(16)
            .maxEuPerTick(in -> 100)
            .noTooltip(ProcessingSpec.Quantity.POWER)
            .build();

        ProcessingRun run = spec.calculate(recipe(30, 100, 0), inputs(VoltageIndex.HV, 0));

        assertEquals(16, run.parallel());
        assertEquals(100, run.euPerTick(), "16 parallels at 30 EU/t would cost 480");
    }

    @Test
    void theVoltageTierReadsTheSummedHatchVoltage() {
        ProcessingInputs twoLuv = ProcessingInputs.builder()
            .energyHatches(VoltageIndex.LuV, 2)
            .build();
        ProcessingInputs mixed = ProcessingInputs.builder()
            .energyHatches(VoltageIndex.LuV, 1)
            .energyHatches(VoltageIndex.IV, 1)
            .build();

        assertEquals(VoltageIndex.ZPM, twoLuv.voltageTier());
        assertEquals((GTValues.V[VoltageIndex.LuV] + GTValues.V[VoltageIndex.IV]) / 2, mixed.averageVoltage());
        assertEquals(4 * GTValues.V[VoltageIndex.LuV], twoLuv.totalEu());
    }

    @Test
    void modesAreWrittenInTheirOwnSections() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .modes(TOWER_AND_DISTILLERY)
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
        assertEquals(
            3,
            tt.getInfoLines()
                .size(),
            "describe adds the tier skips line, not the modes");
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
            .range(ModifierKind.LENGTH, 1, 8)
            .build();

        for (int structure = 0; structure <= 1; structure++) {
            ProcessingInputs inputs = ProcessingInputs.builder()
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

    /** As the steam multiblocks: nothing to say about no overclocks and no tier skips. */
    @Test
    void defaultsNeedNoTooltip() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .noOverclock()
            .maxTierSkips(0)
            .build();

        assertTrue(
            spec.getUndescribed()
                .isEmpty());
        assertTrue(lines(spec).isEmpty());
    }

    /** As the EBF. */
    @Test
    void coilHeatWritesItsVoltageBonusAndItsRules() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .coilHeatPerVoltageTier(
                100,
                VoltageIndex.MV,
                ProcessingSpec.HeatRule.OVERCLOCK,
                ProcessingSpec.HeatRule.DISCOUNT,
                ProcessingSpec.HeatRule.REQUIRED)
            .build();
        ProcessingSpec.Heat heat = spec.getHeat()
            .get();

        assertEquals(
            200,
            heat.getMachineHeat(inputs(VoltageIndex.EV, 3)) - heat.getMachineHeat(inputs(VoltageIndex.MV, 3)));
        assertTrue(
            spec.getUndescribed()
                .isEmpty());
        assertEquals(3, lines(spec).size(), "the voltage bonus, the discount and the overclock");
    }

    /** As the Multi Smelter. */
    @Test
    void recipeCostTermsWriteALine() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .recipeOverride(
                ProcessingSpec.RecipeOverride.eut(4)
                    .duration(128))
            .build();

        assertTrue(
            spec.getUndescribed()
                .isEmpty());
        assertEquals(1, lines(spec).size());
    }

    /** As the Centrifuge's momentum. */
    @Test
    void risingTermsSpanTheirRange() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallelPerVoltageTierRising(4, 8, MOMENTUM, 100)
            .speedRising(2, 3, MOMENTUM, 100)
            .build();

        assertEquals(16, spec.getMaxParallel(atMomentum(0)));
        assertEquals(32, spec.getMaxParallel(atMomentum(100)));
        assertEquals(0.5, spec.getDurationMultiplier(atMomentum(0)));
        assertEquals(1 / 3.0, spec.getDurationMultiplier(atMomentum(100)), 1e-12);
        assertTrue(
            spec.getUndescribed()
                .isEmpty());
    }

    private static ProcessingInputs atMomentum(int momentum) {
        return ProcessingInputs.builder()
            .energyHatches(VoltageIndex.EV, 1)
            .value(MOMENTUM, momentum)
            .build();
    }
}
