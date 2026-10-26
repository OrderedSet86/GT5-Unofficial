package gttests.processingspec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.gtnewhorizons.horizonqa.api.GameTestHelper;
import com.gtnewhorizons.horizonqa.api.annotation.GameTest;
import com.gtnewhorizons.horizonqa.api.annotation.GameTestHolder;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.Mods;
import gregtech.api.enums.VoltageIndex;
import gregtech.api.logic.Modifier;
import gregtech.api.logic.ProcessingInputs;
import gregtech.api.logic.ProcessingRun;
import gregtech.api.logic.ProcessingSpec;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;

/**
 * Checks every registered multiblock with a {@link ProcessingSpec}: the multiblock's tooltip has a line for each number
 * of the spec, and the multiblock's recipe runs match {@link ProcessingSpec#calculate}.
 */
@GameTestHolder(Mods.ModIDs.GREG_TECH)
public class ProcessingSpecConformanceTests {

    private static final int SAMPLE_RECIPES = 8;

    /** @param machine The prototype, for its name, modes and recipe maps. Do not change the prototype. */
    private record Entry(@Nonnull MTEMultiBlockBase machine, @Nonnull ProcessingSpec spec) {}

    @GameTest(batch = "gt5.spec.checks")
    public static void machinesRunAsTheirSpecsSay(GameTestHelper helper) {
        List<String> problems = new ArrayList<>();
        findSpecMachines().forEach(entry -> problems.addAll(findProblems(entry)));
        helper.assertTrue(problems.isEmpty(), String.join("\n", problems));
        helper.succeed();
    }

    private static Stream<Entry> findSpecMachines() {
        return Arrays.stream(GregTechAPI.METATILEENTITIES)
            .filter(MTEMultiBlockBase.class::isInstance)
            .map(MTEMultiBlockBase.class::cast)
            .filter(machine -> !machine.isStructureDeprecated())
            .map(machine -> {
                ProcessingSpec spec = machine.getProcessingSpec();
                return spec == null ? null : new Entry(machine, spec);
            })
            .filter(Objects::nonNull);
    }

    /**
     * The machine's problems: a number of the spec with no tooltip line, and a recipe run that differs from the spec's.
     */
    private static List<String> findProblems(Entry entry) {
        List<String> problems = new ArrayList<>();
        String name = entry.machine()
            .getClass()
            .getSimpleName();
        Set<ProcessingSpec.Quantity> undescribed = entry.spec()
            .getUndescribed();
        if (!undescribed.isEmpty()) {
            problems.add(
                name + " has no tooltip line for " + undescribed + ". Use a Formula term, customTooltip or noTooltip");
        }

        // a machine that throws is one problem, and the remaining machines are still checked
        try {
            String difference = findRunDifference(entry);
            if (difference != null) problems.add(name + " runs a recipe differently from its spec: " + difference);
        } catch (RuntimeException exception) { // noqa: NAR142
            problems.add(name + " cannot be inspected without a world: " + exception);
        }
        return problems;
    }

    /**
     * The first difference between the machine's run and the spec's, or null. The machine is a copy with every modifier
     * at the top of the modifier's range, run in every mode over sampled recipes and energy hatches. Machines without
     * processing logic are only checked to evaluate.
     */
    @Nullable
    private static String findRunDifference(Entry entry) {
        ProcessingSpec spec = entry.spec();
        MTEMultiBlockBase machine = (MTEMultiBlockBase) entry.machine()
            .newMetaEntity(null);
        for (Modifier modifier : machine.getSpecModifiers()) {
            modifier.set(
                spec.getRange(modifier.kind)
                    .max());
        }

        int modes = machine.getMachineModes()
            .size();
        for (int mode = 0; mode < Math.max(1, modes); mode++) {
            if (modes > 1) machine.setMachineMode(mode);
            for (GTRecipe recipe : sampleRecipes(machine.getRecipeMap())) {
                for (List<ProcessingInputs.EnergyHatch> hatches : listEnergySamples()) {
                    // planner-style inputs from the declared kinds alone. calculate() throws if a function reads an
                    // undeclared kind
                    spec.calculate(
                        recipe,
                        spec.buildBestInputs()
                            .energyHatches(hatches)
                            .mode(mode)
                            .build());
                    ProcessingInputs inputs = machine.getCurrentProcessingSpecInputs()
                        .toBuilder()
                        .energyHatches(hatches)
                        .build();
                    ProcessingRun own = machine.calculateForInspection(recipe, inputs);
                    if (own == null) continue;
                    ProcessingRun fromSpec = spec.calculate(recipe, inputs);
                    if (!own.equals(fromSpec)) {
                        return "mode " + mode + ", " + hatches + ": own " + own + ", spec " + fromSpec;
                    }
                }
            }
        }
        return null;
    }

    private static List<List<ProcessingInputs.EnergyHatch>> listEnergySamples() {
        List<List<ProcessingInputs.EnergyHatch>> samples = new ArrayList<>();
        for (int tier : new int[] { VoltageIndex.HV, VoltageIndex.LuV, VoltageIndex.UHV }) {
            ProcessingInputs.EnergyHatch regular = ProcessingInputs.EnergyHatch.regular(tier);
            samples.add(List.of(regular));
            samples.add(List.of(regular, regular));
            samples.add(List.of(ProcessingInputs.EnergyHatch.exotic(tier, 16)));
        }
        return samples;
    }

    /** Spread from the lowest heat and EU/t to the highest. */
    private static List<GTRecipe> sampleRecipes(@Nullable RecipeMap<?> map) {
        if (map == null) return Collections.emptyList();
        List<GTRecipe> recipes = new ArrayList<>(map.getAllRecipes());
        recipes.sort(
            Comparator.comparingInt((GTRecipe recipe) -> recipe.mSpecialValue)
                .thenComparingLong(recipe -> recipe.mEUt));
        if (recipes.size() <= SAMPLE_RECIPES) return recipes;
        List<GTRecipe> sample = new ArrayList<>();
        for (int i = 0; i < SAMPLE_RECIPES; i++) {
            sample.add(recipes.get(i * (recipes.size() - 1) / (SAMPLE_RECIPES - 1)));
        }
        return sample;
    }
}
