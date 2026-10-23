package gregtech.api.logic;

import static gregtech.api.logic.TestRecipes.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.IntFunction;

import net.minecraft.util.EnumChatFormatting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import gregtech.api.enums.GTValues;
import gregtech.api.enums.VoltageIndex;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.RecipeMetadataKey;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.recipe.metadata.SimpleRecipeMetadataKey;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.OverclockCalculator;

/** The rules of {@link ProcessingSpec}: terms, requirements, overclocks, variants and tooltip lines. */
class ProcessingSpecTest {

    // known gap: RecipeMap's constructor is package-private. ProcessingSpec reads only a mode's nameKey
    private static final RecipeMap<?> NO_RECIPE_MAP = mock(RecipeMap.class);
    private static final MachineMode TOWER = new MachineMode(NO_RECIPE_MAP, "Tower", null);
    private static final MachineMode DISTILLERY = new MachineMode(NO_RECIPE_MAP, "Distillery", null);
    private static final MachineMode ORE = new MachineMode(NO_RECIPE_MAP, "Ore", null);
    private static final List<MachineMode> TOWER_AND_DISTILLERY = List.of(TOWER, DISTILLERY);
    private static final RecipeMetadataKey<Boolean> FLAG = SimpleRecipeMetadataKey.create(Boolean.class, "test:flag");

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
    private static final ModifierKind.IntKind WIDE = ModifierKind.ofInt("test:wide")
        .source(ModifierKind.Source.RUNTIME)
        .ordered()
        .range(1, Integer.MAX_VALUE)
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

        assertEquals(4 * VoltageIndex.IV + 3, spec.getMaxParallel(inputs(VoltageIndex.IV, 0)));
        assertEquals(
            1,
            ProcessingSpec.builder()
                .build()
                .getMaxParallel(inputs(5, 3)));
    }

    @Test
    void coilTermsCountFromOne() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .speedPerTier(0, 0.5, ModifierKind.COIL)
            .build();

        // Cupronickel is coil tier 0 and tier 1 on the tooltip
        assertEquals(1 / 0.5, spec.getDurationMultiplier(inputs(VoltageIndex.LV, 0)));
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

        assertEquals(0.4, spec.getDurationMultiplier(inputs(VoltageIndex.LV, 0)));
    }

    @Test
    void resolvingFillsWhatTheSpecLeavesUnsetWithTheDefaults() {
        ResolvedRecipe resolved = ProcessingSpec.builder()
            .euModifier(0.8)
            .unlimitedTierSkips()
            .build()
            .resolve(recipe(30, 200, 0), inputs(VoltageIndex.IV, 3));

        assertEquals(1, resolved.maxParallel());
        assertEquals(1, resolved.durationMultiplier());
        assertEquals(0.8, resolved.euModifier());
        assertEquals(
            new ResolvedRecipe.Overclock(
                ProcessingSpec.OverclockRule.Ratio.STANDARD,
                OptionalInt.empty(),
                Integer.MAX_VALUE,
                null),
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
            .parallel(inputs -> 4 + inputs.value(MOMENTUM), MOMENTUM)
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
            .parallel(inputs -> 4 + inputs.value(MOMENTUM), MOMENTUM)
            .noTooltip(ProcessingSpec.Quantity.PARALLEL)
            .build();

        assertEquals(Map.of(MOMENTUM, new ModifierKind.Range(0, 100)), spec.getModifiers());
        assertThrows(
            IllegalArgumentException.class,
            () -> spec.resolve(recipe(30, 200, 0), inputs(VoltageIndex.IV, 3)));
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
            .parallelPerVoltageTierRising(4, 8, MOMENTUM)
            .whenTier(CASING, 0, tier -> tier.speed(1))
            .whenTier(CASING, 1, tier -> tier.speed(2))
            .build();

        ProcessingInputs best = spec.buildBestInputs()
            .energyHatches(VoltageIndex.EV, 1)
            .build();

        assertEquals(100, best.value(MOMENTUM));
        assertEquals(0, best.value(CASING), "an unordered kind has no best, so it starts at its min");
        assertEquals(32, spec.getMaxParallel(best));
    }

    /** A planner picking the coil for an EBF recipe. */
    @Test
    void theLowestPassingValueMeetsTheRequirements() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .coilHeatPerVoltageTier(100, VoltageIndex.MV, ProcessingSpec.HeatRule.REQUIRED)
            .build();
        ProcessingInputs mv = spec.buildBestInputs()
            .energyHatches(VoltageIndex.MV, 1)
            .build();

        assertEquals(OptionalInt.of(0), spec.findLowestPassing(ModifierKind.COIL, recipe(120, 100, 1700), mv));
        assertEquals(OptionalInt.of(2), spec.findLowestPassing(ModifierKind.COIL, recipe(120, 100, 3600), mv));
        assertEquals(OptionalInt.empty(), spec.findLowestPassing(ModifierKind.COIL, recipe(120, 100, 99_999), mv));
    }

    /** A runtime kind such as the Infinity electrode's parallel ranges up to Integer.MAX_VALUE. */
    @Test
    void aWideRangeIsSearchedInFewChecks() {
        // a binary search over WIDE's range checks about Integer.SIZE values
        final int SEARCH_BUDGET = 2 * Integer.SIZE;
        AtomicInteger checks = new AtomicInteger();
        ProcessingSpec spec = buildCheckCountingSpec(
            checks,
            (inputs, recipe) -> inputs.value(WIDE) >= recipe.mSpecialValue);
        ProcessingSpec never = buildCheckCountingSpec(checks, (inputs, recipe) -> false);
        ProcessingInputs inputs = spec.buildBestInputs()
            .energyHatches(VoltageIndex.LV, 1)
            .build();

        assertEquals(OptionalInt.of(1_000_000), spec.findLowestPassing(WIDE, recipe(30, 100, 1_000_000), inputs));
        assertTrue(checks.get() <= SEARCH_BUDGET, checks + " checks for one search");

        checks.set(0);
        assertEquals(OptionalInt.empty(), never.findLowestPassing(WIDE, recipe(30, 100, 0), inputs));
        assertTrue(checks.get() <= SEARCH_BUDGET, checks + " checks for a recipe no value passes");
    }

    private static ProcessingSpec buildCheckCountingSpec(AtomicInteger checks,
        BiPredicate<ProcessingInputs, GTRecipe> met) {
        return ProcessingSpec.builder()
            .parallel(inputs -> 1, WIDE)
            .requires((inputs, recipe) -> {
                checks.incrementAndGet();
                return met.test(inputs, recipe);
            }, (inputs, recipe) -> CheckRecipeResultRegistry.NO_RECIPE)
            .build();
    }

    @Test
    void onlyAnOrderedKindHasALowestPassingValue() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallel(inputs -> 1, CASING)
            .build();
        ProcessingInputs inputs = spec.buildBestInputs()
            .energyHatches(VoltageIndex.LV, 1)
            .build();

        assertThrows(IllegalArgumentException.class, () -> spec.findLowestPassing(CASING, recipe(30, 100, 0), inputs));
    }

    @Test
    void aSpecWithModeTermsVariesByMode() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .modes(TOWER_AND_DISTILLERY)
            .parallel(16)
            .inMode(DISTILLERY, mode -> mode.parallel(4))
            .build();

        assertTrue(spec.hasModeVariants());
        assertFalse(
            ProcessingSpec.builder()
                .build()
                .hasModeVariants());
    }

    /** Modelled on the steam multiblocks. */
    @Test
    void noOverclockRunsAtTheRecipesOwnVoltageWithItsCost() {
        OverclockCalculator planned = ProcessingSpec.builder()
            .speed(inputs -> 1.25)
            .euModifierNotLimitingParallel(inputs -> 2.5)
            .noOverclock()
            .build()
            .resolve(recipe(16, 200, 0), inputs(VoltageIndex.LV, 0))
            .toCalculator()
            .setParallel(8)
            .calculate();

        assertTrue(planned.isNoOverclock());
        assertEquals(160, planned.getDuration());
        assertEquals(16 * 2.5 * 8, planned.getConsumption());
    }

    /** Modelled on PlanNH's manual rows: a planner changes one calculator setting and keeps the rest of the spec. */
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
            .overclock(4, 4)
            .build();
        ProcessingSpec undescribed = ProcessingSpec.builder()
            .parallel(inputs -> 4)
            .euModifier(inputs -> 0.9)
            .build();
        ProcessingSpec overridden = ProcessingSpec.builder()
            .parallel(inputs -> 4)
            .customTooltip(ProcessingSpec.Quantity.PARALLEL, tt -> tt.addInfo("Four at once"))
            .euModifier(inputs -> 0.9)
            .noTooltip(ProcessingSpec.Quantity.EU_MODIFIER)
            .build();

        assertEquals(EnumSet.noneOf(ProcessingSpec.Quantity.class), described.getUndescribed());
        assertEquals(
            EnumSet.of(ProcessingSpec.Quantity.PARALLEL, ProcessingSpec.Quantity.EU_MODIFIER),
            undescribed.getUndescribed());
        assertEquals(EnumSet.noneOf(ProcessingSpec.Quantity.class), overridden.getUndescribed());
        assertEquals(List.of("Four at once"), lines(overridden));
    }

    /** An LV recipe on an LuV Industrial Forge Hammer with one energy hatch. */
    @ParameterizedTest
    @ValueSource(
        ints = { VoltageIndex.MV, VoltageIndex.HV, VoltageIndex.EV, VoltageIndex.IV, VoltageIndex.LuV,
            VoltageIndex.UMV })
    void calculateFollowsTheParallelAndOverclockLimits(int solenoid) {
        ProcessingSpec forgeHammer = ProcessingSpec.builder()
            .parallel(inputs -> 6 * inputs.voltageTier() * inputs.value(SOLENOID), SOLENOID)
            .speed(2)
            .euModifier(1)
            .build();
        long luv = GTValues.V[VoltageIndex.LuV];

        ProcessingRun run = forgeHammer.calculate(
            recipe(16, 56, 0),
            ProcessingInputs.builder()
                .energyHatches(VoltageIndex.LuV, 1)
                .value(SOLENOID, solenoid)
                .build());

        assertEquals(6 * VoltageIndex.LuV * solenoid, run.parallel());
        assertTrue(run.overclocks() >= 1);
        assertTrue(run.euPerTick() <= luv);
        assertTrue(run.euPerTick() * 4 > luv, "one more overclock would not fit");
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

        assertEquals(GTValues.V[VoltageIndex.LuV] / 480, run.parallel());
        assertEquals(0, run.overclocks());
    }

    @Test
    void inModeReplacesTheSpecsOwnTermsInThatMode() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .modes(TOWER_AND_DISTILLERY)
            .parallel(4)
            .inMode(
                DISTILLERY,
                mode -> mode.parallel(8)
                    .speed(2))
            .build();

        assertEquals(4, spec.getMaxParallel(inMode(0)));
        assertEquals(1, spec.getDurationMultiplier(inMode(0)));
        assertEquals(8, spec.getMaxParallel(inMode(1)));
        assertEquals(0.5, spec.getDurationMultiplier(inMode(1)));
        assertEquals(EnumSet.noneOf(ProcessingSpec.Quantity.class), spec.getUndescribed());

        assertEquals(1, lines(spec).size(), "the spec's own line: a mode's lines go in its own section");
        MultiblockTooltipBuilder distillery = new MultiblockTooltipBuilder();
        spec.describeMode(distillery, DISTILLERY);
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
                .inMode(DISTILLERY, mode -> mode.parallel(8))
                .build());
        assertThrows(
            IllegalArgumentException.class,
            () -> ProcessingSpec.builder()
                .modes(TOWER_AND_DISTILLERY)
                .inMode(ORE, mode -> mode.parallel(8))
                .build());
    }

    @Test
    void aModeIsListedOnce() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ProcessingSpec.builder()
                .modes(List.of(TOWER, TOWER)));
    }

    @Test
    void requirementsDecideWhetherARecipeRuns() {
        ProcessingSpec spec = ProcessingSpec.builder()
            // Cupronickel coils: 1801K
            .coilHeatPerVoltageTier(0, VoltageIndex.EV, ProcessingSpec.HeatRule.REQUIRED)
            .requires((inputs, recipe) -> recipe.mEUt <= 30, (inputs, recipe) -> CheckRecipeResultRegistry.NO_RECIPE)
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
    void termsThatReadTheRecipeApplyOnceItIsKnown() {
        ProcessingSpec parallel = ProcessingSpec.builder()
            .parallel(2)
            .parallelPerRecipe((inputs, recipe) -> recipe.mDuration / 100)
            .noTooltip(ProcessingSpec.Quantity.PARALLEL)
            .build();
        ProcessingInputs iv = inputs(VoltageIndex.IV, 0);

        assertEquals(2, parallel.getMaxParallel(iv), "without a recipe, the per-recipe term is left out");
        assertEquals(
            6,
            parallel.resolve(recipe(30, 400, 0), iv)
                .maxParallel());

        ProcessingSpec timing = ProcessingSpec.builder()
            .maxOverclocksPerRecipe((inputs, recipe) -> 1)
            .noTooltip(ProcessingSpec.Quantity.OVERCLOCK)
            .build();
        ProcessingRun run = timing.calculate(recipe(30, 400, 0), iv);

        // one overclock where IV would allow four
        assertEquals(1, run.overclocks());
        assertEquals(200, run.ticks());
        assertEquals(120, run.euPerTick());
    }

    @Test
    void aRecipeAboveTheVoltageLacksPower() {
        GTRecipe recipe = recipe(33, 200, 0);

        assertEquals(
            CheckRecipeResultRegistry.insufficientPower(33),
            buildThirtyTwoVoltSpec().check(recipe, inputs(VoltageIndex.LV, 0)));
    }

    @Test
    void aRecipeAtTheVoltageRuns() {
        assertTrue(
            buildThirtyTwoVoltSpec().check(recipe(32, 200, 0), inputs(VoltageIndex.LV, 0))
                .wasSuccessful());
    }

    @Test
    void aFlaggedRecipeIsRefused() {
        GTRecipe recipe = recipe(30, 200, 0);
        when(recipe.getMetadataOrDefault(FLAG, false)).thenReturn(true);

        assertEquals(
            CheckRecipeResultRegistry.NO_RECIPE,
            buildFlagRefusingSpec().check(recipe, inputs(VoltageIndex.LV, 0)));
    }

    @Test
    void anUnflaggedRecipeRuns() {
        GTRecipe recipe = recipe(30, 200, 0);
        when(recipe.getMetadataOrDefault(FLAG, false)).thenReturn(false);

        assertTrue(
            buildFlagRefusingSpec().check(recipe, inputs(VoltageIndex.LV, 0))
                .wasSuccessful());
    }

    private static ProcessingSpec buildThirtyTwoVoltSpec() {
        return ProcessingSpec.builder()
            .power(inputs -> 32, inputs -> 1)
            .noTooltip(ProcessingSpec.Quantity.POWER)
            .requiresRecipeWithinVoltage()
            .build();
    }

    private static ProcessingSpec buildFlagRefusingSpec() {
        return ProcessingSpec.builder()
            .refusesRecipesFlagged(FLAG)
            .build();
    }

    @Test
    void termsThatReadTheRecipeApplyInEveryMode() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ProcessingSpec.builder()
                .modes(TOWER_AND_DISTILLERY)
                .inMode(TOWER, mode -> mode.parallelPerRecipe((inputs, recipe) -> 4)));
        assertThrows(
            IllegalArgumentException.class,
            () -> ProcessingSpec.builder()
                .modes(TOWER_AND_DISTILLERY)
                .inMode(TOWER, mode -> mode.requires((inputs, recipe) -> true, (inputs, recipe) -> null)));
    }

    @Test
    void standardPowerUsesOneAmpOfALoneRegularHatch() throws ReflectiveOperationException {
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
        assertFalse(sets(spec, ProcessingSpec.Quantity.POWER));
    }

    @Test
    void powerRulesReadTheHatchesAsTheMachineDoes() throws ReflectiveOperationException {
        ProcessingInputs oneHatch = inputs(VoltageIndex.LuV, 0);
        long luv = GTValues.V[VoltageIndex.LuV];

        ProcessingSpec atOneAmp = ProcessingSpec.builder()
            .powerAtOneAmp()
            .build();
        assertEquals(new ProcessingSpec.Power(2 * luv, 1, true), atOneAmp.getPower(oneHatch));
        assertTrue(sets(atOneAmp, ProcessingSpec.Quantity.POWER));

        ProcessingSpec allAmps = ProcessingSpec.builder()
            .allAmps()
            .build();
        assertEquals(new ProcessingSpec.Power(luv, 2, true), allAmps.getPower(oneHatch));

        ProcessingSpec fixed = ProcessingSpec.builder()
            .power(inputs -> 32, inputs -> 8)
            .noAmperageOverclock()
            .build();
        assertEquals(new ProcessingSpec.Power(32, 8, false), fixed.getPower(oneHatch));
    }

    @Test
    void theOverclockCanDifferByTier() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .whenTier(ModifierKind.COIL, 1, tier -> tier.overclock(1, 4))
            .noTooltip(ProcessingSpec.Quantity.OVERCLOCK)
            .build();
        GTRecipe recipe = recipe(30, 400, 0);

        assertEquals(ProcessingSpec.OverclockRule.Ratio.STANDARD, spec.getOverclock(inputs(VoltageIndex.HV, 0)));
        assertEquals(new ProcessingSpec.OverclockRule.Ratio(1, 4), spec.getOverclock(inputs(VoltageIndex.HV, 1)));
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
            .inMode(DISTILLERY, mode -> mode.recipeEuMultiplier(16))
            .requires(
                (inputs, recipe) -> recipe.mEUt <= inputs.averageVoltage(),
                (inputs, recipe) -> CheckRecipeResultRegistry.NO_RECIPE)
            .noTooltip(ProcessingSpec.Quantity.RECIPE_EU_MULTIPLIER)
            .build();
        GTRecipe recipe = recipe(30, 400, 0);
        ProcessingInputs ev = ProcessingInputs.builder()
            .energyHatches(VoltageIndex.EV, 1)
            .mode(1)
            .build();

        ProcessingRun run = spec.calculate(recipe, ev);

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
            .maxOverclocksPerRecipe((inputs, recipe) -> 2)
            .unlimitedEnergy(inputs -> inputs.value(ModifierKind.COIL) == 1)
            .noTooltip(ProcessingSpec.Quantity.OVERCLOCK, ProcessingSpec.Quantity.POWER)
            .build();
        GTRecipe recipe = recipe(480, 400, 0);

        ProcessingRun limited = spec.calculate(recipe, inputs(VoltageIndex.LuV, 0));
        ProcessingRun unlimited = spec.calculate(recipe, inputs(VoltageIndex.LuV, 1));

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
                (inputs, recipe) -> inputs.totalEu() >= 10_000,
                (inputs, recipe) -> CheckRecipeResultRegistry.NO_RECIPE)
            .noTooltip(ProcessingSpec.Quantity.POWER)
            .build();
        GTRecipe recipe = recipe(30, 100, 0);
        ProcessingInputs hv = inputs(VoltageIndex.HV, 0);

        assertTrue(
            spec.check(recipe, hv)
                .wasSuccessful());
        assertFalse(
            spec.resolve(recipe, hv)
                .checkToStart()
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
    void aModeOutsideRecipeProcessingIsNotCalculated() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .modes(List.of(TOWER, DISTILLERY, ORE))
            .notCalculatedIn(ORE)
            .build();

        assertTrue(spec.canCalculate(0));
        assertFalse(spec.canCalculate(2));
        assertTrue(
            spec.calculate(recipe(30, 100, 0), inMode(0))
                .result()
                .wasSuccessful());
        assertThrows(IllegalArgumentException.class, () -> spec.calculate(recipe(30, 100, 0), inMode(2)));
        assertThrows(IllegalArgumentException.class, () -> spec.resolve(recipe(30, 100, 0), inMode(2)));
    }

    @Test
    void aRunDrawsNoMoreThanTheCap() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallel(16)
            .euPerTickCap(inputs -> 100)
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

        assertEquals(VoltageIndex.ZPM, twoLuv.voltageTier());
    }

    @Test
    void theAverageVoltageIsTheMeanOfTheHatches() {
        ProcessingInputs mixed = ProcessingInputs.builder()
            .energyHatches(VoltageIndex.LuV, 1)
            .energyHatches(VoltageIndex.IV, 1)
            .build();

        assertEquals((GTValues.V[VoltageIndex.LuV] + GTValues.V[VoltageIndex.IV]) / 2, mixed.averageVoltage());
    }

    @Test
    void theTotalEuCountsEveryAmpOfEveryHatch() {
        ProcessingInputs twoLuv = ProcessingInputs.builder()
            .energyHatches(VoltageIndex.LuV, 2)
            .build();

        // 2 A for each regular hatch
        assertEquals(2 * 2 * GTValues.V[VoltageIndex.LuV], twoLuv.totalEu());
    }

    @Test
    void theRunCarriesTheStartupEu() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .startupEuPerRecipe((inputs, recipe) -> 1000L * recipe.mEUt)
            .requires((inputs, recipe) -> recipe.mEUt <= 30, (inputs, recipe) -> CheckRecipeResultRegistry.NO_RECIPE)
            .noTooltip(ProcessingSpec.Quantity.POWER)
            .build();
        ProcessingInputs iv = inputs(VoltageIndex.IV, 0);

        assertEquals(
            BigInteger.valueOf(30_000),
            spec.calculate(recipe(30, 100, 0), iv)
                .eu()
                .startup());
        assertEquals(
            BigInteger.ZERO,
            spec.calculate(recipe(120, 100, 0), iv)
                .eu()
                .startup(),
            "a recipe that cannot run starts nothing");
    }

    @Test
    void modesAreWrittenInTheirOwnSections() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .modes(TOWER_AND_DISTILLERY)
            .unlimitedTierSkips()
            .inMode(TOWER, mode -> mode.parallel(4))
            .inMode(
                DISTILLERY,
                mode -> mode.parallel(8)
                    .speed(2))
            .build();
        MultiblockTooltipBuilder tt = new MultiblockTooltipBuilder();

        spec.describeMode(tt, DISTILLERY);
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
        assertThrows(IllegalArgumentException.class, () -> spec.describeMode(new MultiblockTooltipBuilder(), ORE));
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

        IntFunction<ProcessingInputs> atLengthThree = casingTier -> ProcessingInputs.builder()
            .value(CASING, casingTier)
            .value(ModifierKind.LENGTH, 3)
            .build();

        assertEquals(16 + 8 * 3, spec.getMaxParallel(atLengthThree.apply(0)));
        assertEquals(32 + 16 * 3, spec.getMaxParallel(atLengthThree.apply(1)));

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
            .inMode(DISTILLERY, mode -> mode.parallel(inputs -> 3))
            .build();
        ProcessingSpec coveredForEveryMode = ProcessingSpec.builder()
            .modes(TOWER_AND_DISTILLERY)
            .inMode(DISTILLERY, mode -> mode.parallel(inputs -> 3))
            .noTooltip(ProcessingSpec.Quantity.PARALLEL)
            .build();

        assertEquals(EnumSet.of(ProcessingSpec.Quantity.PARALLEL), undescribed.getUndescribed());
        assertEquals(EnumSet.noneOf(ProcessingSpec.Quantity.class), coveredForEveryMode.getUndescribed());
        assertThrows(
            IllegalArgumentException.class,
            () -> ProcessingSpec.builder()
                .modes(TOWER_AND_DISTILLERY)
                .inMode(DISTILLERY, mode -> mode.maxTierSkips(2)));
    }

    /** Modelled on the steam multiblocks: no overclocks and no tier skips, and no tooltip line for either. */
    @Test
    void defaultsNeedNoTooltip() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .noOverclock()
            .maxTierSkips(0)
            .build();

        assertEquals(EnumSet.noneOf(ProcessingSpec.Quantity.class), spec.getUndescribed());
        assertTrue(lines(spec).isEmpty());
    }

    /** Modelled on fusion MK1 to MK3. */
    @Test
    void aNonStandardOverclockRatioWritesALine() {
        ProcessingSpec ratio = ProcessingSpec.builder()
            .overclock(2, 2)
            .build();
        ProcessingSpec undescribedCap = ProcessingSpec.builder()
            .overclock(2, 2)
            .maxOverclocksPerRecipe((inputs, recipe) -> 1)
            .build();
        ProcessingSpec describedCap = ProcessingSpec.builder()
            .overclock(2, 2)
            .maxOverclocksPerRecipe((inputs, recipe) -> 1, tt -> tt.addInfo("cap"))
            .build();

        assertTrue(
            ratio.getUndescribed()
                .isEmpty());
        assertEquals(1, lines(ratio).size());
        assertEquals(EnumSet.of(ProcessingSpec.Quantity.OVERCLOCK), undescribedCap.getUndescribed());
        assertEquals(2, lines(describedCap).size());
    }

    /** Modelled on the EBF. */
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
        assertEquals(EnumSet.noneOf(ProcessingSpec.Quantity.class), spec.getUndescribed());
        assertEquals(4, lines(spec).size(), "the coils, the voltage bonus, the discount and the overclock");
    }

    /** Modelled on the Multi Smelter and the Arc Furnace's blast mode. */
    @Test
    void recipeCostTermsWriteALine() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .recipeOverride(4, 128)
            .recipeEuMultiplier(16)
            .build();

        assertEquals(EnumSet.noneOf(ProcessingSpec.Quantity.class), spec.getUndescribed());
        assertEquals(2, lines(spec).size());
    }

    /** Modelled on fusion. The fusion markdown has the parallel and the power lines. */
    @Test
    void oneCustomTooltipCanCoverSeveralQuantities() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallel(inputs -> 4)
            .speed(inputs -> 2)
            .customTooltip(
                EnumSet.of(ProcessingSpec.Quantity.PARALLEL, ProcessingSpec.Quantity.DURATION),
                tt -> tt.addInfo("block"))
            .build();

        assertTrue(
            spec.getUndescribed()
                .isEmpty());
        assertEquals(List.of("block"), lines(spec));
    }

    /** Modelled on the Centrifuge's momentum. */
    @Test
    void risingTermsSpanTheirRange() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallelPerVoltageTierRising(4, 8, MOMENTUM)
            .speedRising(2, 3, MOMENTUM)
            .build();

        assertEquals(16, spec.getMaxParallel(atMomentum(0)));
        assertEquals(32, spec.getMaxParallel(atMomentum(100)));
        assertEquals(0.5, spec.getDurationMultiplier(atMomentum(0)));
        assertEquals(1 / 3.0, spec.getDurationMultiplier(atMomentum(100)), 1e-12);
        assertEquals(EnumSet.noneOf(ProcessingSpec.Quantity.class), spec.getUndescribed());
    }

    @Test
    void aKindWithARangeTakesNoSpecRange() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ProcessingSpec.builder()
                .range(MOMENTUM, 0, 50));
    }

    @Test
    void aRisingTermNeedsAKindWithARange() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ProcessingSpec.builder()
                .speedRising(2, 3, ModifierKind.LENGTH));
        assertThrows(
            IllegalArgumentException.class,
            () -> ProcessingSpec.builder()
                .parallelPerVoltageTierRising(4, 8, ModifierKind.LENGTH));
        assertThrows(IllegalArgumentException.class, () -> new Formula.Rising(2, 3, ModifierKind.LENGTH));
    }

    private static ProcessingInputs atMomentum(int momentum) {
        return ProcessingInputs.builder()
            .energyHatches(VoltageIndex.EV, 1)
            .value(MOMENTUM, momentum)
            .build();
    }

    /** Calls the private {@code ProcessingSpec.sets()}. */
    private static boolean sets(ProcessingSpec spec, ProcessingSpec.Quantity quantity)
        throws ReflectiveOperationException {
        Method sets = ProcessingSpec.class.getDeclaredMethod("sets", ProcessingSpec.Quantity.class);
        sets.setAccessible(true);
        return (boolean) sets.invoke(spec, quantity);
    }
}
