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
import gregtech.api.util.tooltip.TooltipTier;

class ProcessingSpecTest {

    private static ProcessingSpec.Inputs inputs(int voltageTier, int coilTier) {
        return ProcessingSpec.Inputs.builder()
            .voltageTier(voltageTier)
            .tier(TooltipTier.COIL, coilTier)
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
            .tier(TooltipTier.SOLENOID, VoltageIndex.LuV)
            .build();

        assertEquals(VoltageIndex.LuV, luv.tier(TooltipTier.VOLTAGE));
        assertEquals(VoltageIndex.LuV, luv.tier(TooltipTier.SOLENOID));
        assertEquals(1, luv.amperage());
        assertEquals(0, luv.mode());
        assertThrows(IllegalArgumentException.class, () -> luv.tier(TooltipTier.COIL));
    }

    @Test
    void parallelTermsAddUpAndNeverFallBelowOne() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallelPerTier(4, TooltipTier.VOLTAGE)
            .parallel(3)
            .build();

        assertEquals(4 * 5 + 3, spec.getMaxParallel(inputs(5, 0)));
        assertEquals(1, ProcessingSpec.STANDARD.getMaxParallel(inputs(5, 3)));
    }

    @Test
    void parallelPerTierMultipliesItsTiers() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallelPerTier(6, TooltipTier.VOLTAGE, TooltipTier.SOLENOID)
            .build();
        ProcessingSpec.Inputs luv = ProcessingSpec.Inputs.builder()
            .voltageTier(VoltageIndex.LuV)
            .tier(TooltipTier.SOLENOID, VoltageIndex.LuV)
            .build();

        assertEquals(6 * 6 * 6, spec.getMaxParallel(luv));
    }

    @Test
    void coilTermsCountFromOne() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .speedPerTier(0.5, TooltipTier.COIL)
            .euDiscountPerTier(0.1, TooltipTier.COIL)
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
                    in -> 1000 * in.tier(TooltipTier.COIL),
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
    void bestCaseNumbersAreLeftToTheMachine() {
        ProcessingLogic logic = new ProcessingLogic().setSpeedBonus(0.5)
            .setMaxParallel(7)
            .applySpec(
                ProcessingSpec.builder()
                    .parallel(64)
                    .speed(4)
                    .bestCase(ProcessingSpec.Quantity.PARALLEL, ProcessingSpec.Quantity.DURATION)
                    .build(),
                () -> inputs(5, 3));

        OverclockCalculator calculator = logic.createOverclockCalculatorForInspection(recipe(30, 200, 0));

        assertEquals(7, logic.getResolvedMaxParallel());
        assertEquals(0.5, calculator.getDurationModifier());
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
            .parallelPerTier(4, TooltipTier.VOLTAGE)
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
            .parallelPerTier(6, TooltipTier.VOLTAGE, TooltipTier.SOLENOID)
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
                    .tier(TooltipTier.SOLENOID, row[0])
                    .build());

            assertEquals(row[1], run.parallel(), "parallel at solenoid " + row[0]);
            assertEquals(row[2], run.overclocks(), "overclocks at solenoid " + row[0]);
            assertEquals(row[3], run.ticks(), "ticks at solenoid " + row[0]);
            assertEquals(row[4], run.euPerTick(), "EU/t at solenoid " + row[0]);
        }
    }

    @Test
    void calculateTakesBestCaseNumbersAtTheirBest() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .parallel(4)
            .speed(2)
            .bestCase(ProcessingSpec.Quantity.PARALLEL, ProcessingSpec.Quantity.DURATION)
            .build();

        ProcessingSpec.Run run = spec.calculate(
            recipe(30, 200, 0),
            ProcessingSpec.Inputs.builder()
                .voltageTier(VoltageIndex.HV)
                .build());

        // 4 x 30 EU/t leaves room for one overclock at HV: 200 ticks at 2x speed, then halved
        assertEquals(4, run.parallel());
        assertEquals(50, run.ticks());
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
    void whenTierAppliesAtThatStructureValue() {
        ProcessingSpec spec = ProcessingSpec.builder()
            .whenTier(
                TooltipTier.STRUCTURE,
                0,
                "Heat Resistant Casing",
                tier -> tier.parallel(16)
                    .parallelPerTier(8, TooltipTier.LENGTH))
            .whenTier(
                TooltipTier.STRUCTURE,
                1,
                "Heat Proof Casing",
                tier -> tier.parallel(32)
                    .parallelPerTier(16, TooltipTier.LENGTH))
            .build();

        for (int structure = 0; structure <= 1; structure++) {
            ProcessingSpec.Inputs inputs = ProcessingSpec.Inputs.builder()
                .tier(TooltipTier.STRUCTURE, structure)
                .tier(TooltipTier.LENGTH, 3)
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
            .speedPerTierBeyondFirst(1.5, 0.1, TooltipTier.COIL)
            .euModifierPerTierBeyondFirst(0.8, 0.9, TooltipTier.COIL)
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
