package gregtech.api.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.VoltageIndex;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;

/** The registered multiblocks with a {@link ProcessingSpec}, and the check that each machine's runs match its spec. */
public final class ProcessingSpecs {

    private static final int SAMPLE_RECIPES = 8;
    private static final List<List<ProcessingInputs.EnergyHatch>> ENERGY_SAMPLES = createEnergySamples();

    /** @param machine The prototype, for its name, modes and recipe maps. Do not change it. */
    public record Entry(@Nonnull MTEMultiBlockBase machine, @Nonnull ProcessingSpec spec) {}

    private ProcessingSpecs() {}

    @Nonnull
    public static Stream<Entry> all() {
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

    public static void check() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Processing spec problems:\n  " + String.join("\n  ", problems));
        }
    }

    /**
     * Tooltips missing a number their spec sets, machines whose modifiers differ from the kinds their spec reads, and
     * machines that run a recipe differently from their spec, over sampled recipes and energy hatches.
     */
    @Nonnull
    public static List<String> problems() {
        List<String> problems = new ArrayList<>();
        all().forEach(entry -> problems.addAll(problems(entry)));
        return problems;
    }

    @Nonnull
    public static List<String> problems(@Nonnull Entry entry) {
        List<String> problems = new ArrayList<>();
        String name = entry.machine()
            .getClass()
            .getSimpleName();
        Set<ProcessingSpec.Quantity> undescribed = entry.spec()
            .getUndescribed();
        if (!undescribed.isEmpty()) {
            problems.add(
                name + " sets "
                    + undescribed
                    + " but its tooltip does not show them; use a term that describes itself,"
                    + " customTooltip or noTooltip");
        }
        Set<ModifierKind> declared = new HashSet<>();
        for (ModifierRange range : entry.spec()
            .getModifiers()) declared.add(range.kind());
        Set<ModifierKind> kept = new HashSet<>();
        for (Modifier modifier : entry.machine()
            .getSpecModifiers()) kept.add(modifier.kind);
        if (!declared.equals(kept)) {
            problems.add(name + " keeps modifiers " + kept + " but its spec reads " + declared);
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
     * On a copy of the machine with every modifier at the top of its range, in every mode the spec supports, over
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
            for (GTRecipe recipe : sample(modes > 1 ? machine.getRecipeMapForMode(mode) : machine.getRecipeMap())) {
                for (List<ProcessingInputs.EnergyHatch> hatches : ENERGY_SAMPLES) {
                    // as a planner does, from the declared kinds alone: throws if a function reads another
                    spec.calculate(
                        recipe,
                        spec.bestInputs()
                            .energyHatches(hatches)
                            .mode(mode)
                            .build());
                    machine.setEnergyHatchesForInspection(hatches);
                    ProcessingRun own = machine.calculateForInspection(recipe);
                    if (own == null) continue;
                    ProcessingRun fromSpec = spec.calculate(recipe, machine.getCurrentProcessingSpecInputs());
                    if (!isSameRun(own, fromSpec)) {
                        return "mode " + mode + ", " + hatches + ": own " + own + ", spec " + fromSpec;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isSameRun(ProcessingRun a, ProcessingRun b) {
        return a.result()
            .wasSuccessful()
            == b.result()
                .wasSuccessful()
            && a.result()
                .getID()
                .equals(
                    b.result()
                        .getID())
            && a.parallel() == b.parallel()
            && a.overclocks() == b.overclocks()
            && a.ticks() == b.ticks()
            && a.euPerTick() == b.euPerTick()
            && a.eu()
                .equals(b.eu());
    }

    /** One regular hatch, two, and a 16 A hatch, at three tiers. */
    private static List<List<ProcessingInputs.EnergyHatch>> createEnergySamples() {
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
