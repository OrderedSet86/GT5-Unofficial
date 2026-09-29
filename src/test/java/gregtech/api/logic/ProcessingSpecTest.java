package gregtech.api.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.EnumSet;
import java.util.List;

import net.minecraft.util.EnumChatFormatting;

import org.junit.jupiter.api.Test;

import gregtech.api.enums.VoltageIndex;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.api.util.OverclockCalculator;

class ProcessingSpecTest {

    private static final ModifierKind MOMENTUM = ModifierKind.builder("test:momentum")
        .source(ModifierKind.Source.RUNTIME)
        .ordered()
        .register();
    private static final ModifierKind CASING = ModifierKind.builder("test:casing")
        .labels(0, "Heat Resistant Casing", "Heat Proof Casing")
        .register();

    private static ProcessingSpec.Inputs inputs(int voltageTier, int coilTier) {
        return ProcessingSpec.Inputs.builder()
            .voltageTier(voltageTier)
            .value(ModifierKind.COIL, coilTier)
            .build();
    }

    private static ProcessingSpec.Inputs inMode(int mode) {
        return ProcessingSpec.Inputs.builder()
            .voltageTier(VoltageIndex.LV)
            .mode(mode)
            .build();
    }

    private static List<String> lines(ProcessingSpec spec) {
        MultiblockTooltipBuilder tt = new MultiblockTooltipBuilder();
        spec.describe(tt);
        return tt.getInfoLines();
    }

    private static GTRecipe recipe(int eut, int duration, int heat) {
        // GTRecipe's constructor needs the game loaded; these are the only fields the calculator reads.
        GTRecipe recipe = mock(GTRecipe.class);
        recipe.mEUt = eut;
        recipe.mDuration = duration;
        recipe.mSpecialValue = heat;
        return recipe;
    }

    @Test
    void inputsNameEveryValue() {
        ProcessingSpec.Inputs luv = ProcessingSpec.Inputs.builder()
            .voltageTier(VoltageIndex.LuV)
            .value(ModifierKind.SOLENOID, VoltageIndex.LuV)
            .build();

        assertEquals(VoltageIndex.LuV, luv.value(ModifierKind.VOLTAGE));
        assertEquals(VoltageIndex.LuV, luv.value(ModifierKind.SOLENOID));
        assertEquals(1, luv.amperage());
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
            .voltageTier(VoltageIndex.LuV)
            .value(ModifierKind.SOLENOID, VoltageIndex.LuV)
            .build();

        assertEquals(6 * 6 * 6, spec.getMaxParallel(luv));
    }

    @Test
    void coilTermsCountFromOne() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .speedPerTier(0.5, ModifierKind.COIL)
            .euDiscountPerTier(0.1, ModifierKind.COIL)
            .maxEuDiscount(0.5)
            .build();

        // Cupronickel is coil tier 0 and tier 1 on the tooltip
        assertEquals(1 / 0.5, spec.getDurationMultiplier(inputs(1, 0)));
        assertEquals(0.9, spec.getEuModifier(inputs(1, 0)), 1e-12);
        assertEquals(0.5, spec.getEuModifier(inputs(1, 9)));
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
                () -> inputs(5, 3));

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
            () -> inputs(5, 3));

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
        int[] momentum = { 0 };
        ProcessingLogic logic = new ProcessingLogic().applySpec(
            ProcessingSpec.builder()
                .parallel(in -> 4 + in.value(MOMENTUM))
                .noTooltip(ProcessingSpec.Quantity.PARALLEL)
                .build(),
            () -> ProcessingSpec.Inputs.builder()
                .value(MOMENTUM, momentum[0])
                .build());

        logic.createOverclockCalculatorForInspection(recipe(30, 200, 0));
        assertEquals(4, logic.getResolvedMaxParallel());

        momentum[0] = 100;
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
                () -> inputs(1, 0));
        GTRecipe recipe = recipe(16, 200, 0);

        OverclockCalculator planned = logic.createOverclockCalculatorForInspection(recipe)
            .setParallel(8)
            .calculate();
        OverclockCalculator expected = OverclockCalculator.ofNoOverclock(recipe)
            .setEUtDiscount(2.5)
            .setDurationModifier(0.8)
            .setParallel(8)
            .calculate();

        assertTrue(planned.isNoOverclock());
        assertEquals(expected.getMaxAllowedRecipeEUt(), planned.getMaxAllowedRecipeEUt());
        assertEquals(expected.getDuration(), planned.getDuration());
        assertEquals(expected.getConsumption(), planned.getConsumption());
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
                    .voltageTier(VoltageIndex.LuV)
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
                .voltageTier(VoltageIndex.LuV)
                .build());

        assertEquals(32768 / 480, run.parallel());
        assertEquals(0, run.overclocks());
    }

    @Test
    void inModeReplacesTheSpecsOwnTermsInThatMode() {
        List<MachineMode> modes = List.of(
            MachineMode.of(mock(RecipeMap.class))
                .nameKey("Tower"),
            MachineMode.of(mock(RecipeMap.class))
                .nameKey("Distillery"));
        ProcessingSpec spec = ProcessingSpec.builder()
            .modes(modes)
            .parallel(4)
            .inMode(
                1,
                mode -> mode.parallel(8)
                    .speed(2))
            .inMode(2, mode -> mode.parallel(16))
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
        assertEquals(3, lines.size(), "the spec's own line and the Distillery's two; the machine has no mode 2");
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
            .inMode(1, mode -> mode.parallel(in -> 3))
            .build();
        ProcessingSpec coveredForEveryMode = ProcessingSpec.builder()
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
                .inMode(1, mode -> mode.perfectOverclock()));
    }

    /** As the Large Fluid Extractor: Cupronickel coils give the base speed and EU. */
    @Test
    void beyondFirstTermsStartAtTheFirstTier() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .speedPerTierBeyondFirst(1.5, 0.1, ModifierKind.COIL)
            .euModifierPerTierBeyondFirst(0.8, 0.9, ModifierKind.COIL)
            .build();

        assertEquals(1.0 / 1.5, spec.getDurationMultiplier(inputs(1, 0)));
        assertEquals(0.8, spec.getEuModifier(inputs(1, 0)));
        for (int coil = 0; coil <= 13; coil++) {
            assertEquals(1.0 / (1.5 + 0.1 * coil), spec.getDurationMultiplier(inputs(1, coil)));
            assertEquals(0.8 * GTUtility.powInt(0.9, coil), spec.getEuModifier(inputs(1, coil)));
        }
        assertTrue(
            spec.getUndescribed()
                .isEmpty());
    }
}
