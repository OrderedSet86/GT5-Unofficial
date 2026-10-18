package gttests.processingspec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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

/** That each registered multiblock with a {@link ProcessingSpec} runs recipes as its spec says. */
@GameTestHolder(Mods.ModIDs.GREG_TECH)
public class ProcessingSpecConformanceTests {

    private static final int SAMPLE_RECIPES = 8;

    /** @param machine The prototype, for its name, modes and recipe maps. Do not change it. */
    record Entry(@Nonnull MTEMultiBlockBase machine, @Nonnull ProcessingSpec spec) {}

    @GameTest(batch = "gt5.spec.checks")
    public static void machinesRunAsTheirSpecsSay(GameTestHelper helper) {
        List<String> problems = new ArrayList<>();
        all().forEach(entry -> problems.addAll(problems(entry)));
        helper.assertTrue(problems.isEmpty(), String.join("\n", problems));
        helper.succeed();
    }

    static Stream<Entry> all() {
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

    /** Tooltips missing a number their spec sets, and machines that run a recipe differently from their spec. */
    private static List<String> problems(Entry entry) {
        List<String> problems = new ArrayList<>();
        String name = entry.machine()
            .getClass()
            .getSimpleName();
        if (!entry.spec()
            .getUndescribed()
            .isEmpty()) {
            problems.add(
                name + " sets "
                    + entry.spec()
                        .getUndescribed()
                    + " but its tooltip does not show them; use a term that describes itself,"
                    + " customTooltip or noTooltip");
        }
        try {
            String run = findRunDifference(entry);
            if (run != null) problems.add(name + " runs a recipe differently from its spec: " + run);
        } catch (RuntimeException e) {
            problems.add(name + " cannot be inspected without a world: " + e);
        }
        return problems;
    }

    /**
     * On a copy of the machine with every modifier at the top of its range, in every mode, over
     * sampled recipes and energy hatches. Machines without processing logic are only checked to evaluate.
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
            if (!spec.supportsMode(mode)) continue;
            if (modes > 1) machine.setMachineMode(mode);
            for (GTRecipe recipe : sample(machine.getRecipeMap())) {
                for (List<ProcessingInputs.EnergyHatch> hatches : energySamples()) {
                    // as a planner does, from the declared kinds alone: throws if a function reads another
                    spec.calculate(
                        recipe,
                        spec.bestInputs()
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

    /** One regular hatch, two, and a 16 A hatch, at three tiers. */
    private static List<List<ProcessingInputs.EnergyHatch>> energySamples() {
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
    private static List<GTRecipe> sample(@Nullable RecipeMap<?> map) {
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
