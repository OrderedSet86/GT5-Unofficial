package gregtech.api.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
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
import gregtech.api.util.OverclockCalculator;

/** The registered multiblocks with a {@link ProcessingSpec}. */
public final class ProcessingSpecs {

    private static final int SAMPLE_RECIPES = 8;
    private static final List<List<ProcessingSpec.EnergyHatch>> ENERGY_SAMPLES = energySamples();

    /** @param machine The prototype: use {@link MTEMultiBlockBase#newMetaEntity} before changing anything */
    public record Entry(@Nonnull MTEMultiBlockBase machine, @Nonnull ProcessingSpec spec,
        @Nonnull List<Modifier<?>> modifiers) {}

    private ProcessingSpecs() {}

    @Nonnull
    public static Stream<Entry> all() {
        return Arrays.stream(GregTechAPI.METATILEENTITIES)
            .filter(MTEMultiBlockBase.class::isInstance)
            .map(MTEMultiBlockBase.class::cast)
            .map(machine -> {
                ProcessingSpec spec = machine.getProcessingSpec();
                return spec == null ? null : new Entry(machine, spec, machine.getModifiersForInspection());
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
     * Tooltips missing a number their spec sets, and machines whose own calculator, max parallel or whole run differs
     * from their spec's without {@link ProcessingSpec.Builder#alsoCustom}. They are compared on a copy with every
     * modifier at its maximum, in every mode the spec supports, over sampled recipes; runs also over sampled energy
     * hatches.
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
                    + " but its tooltip does not show them; add a term that describes itself,"
                    + " customTooltip or noTooltip");
        }
        Set<ProcessingSpec.Quantity> custom = undeclaredCustom(entry);
        if (!custom.isEmpty()) {
            problems.add(
                name + " sets its own "
                    + custom
                    + " outside its spec; declare them in its spec or mark it alsoCustom("
                    + custom
                    + ")");
        } else if (entry.spec()
            .isComplete()) {
                String run = runDifference(entry);
                if (run != null) problems.add(name + " runs a recipe differently from its spec: " + run);
            }
        return problems;
    }

    /** Null if every run matches, or for a machine that cannot be inspected without a world. */
    @Nullable
    private static String runDifference(Entry entry) {
        try {
            MTEMultiBlockBase machine = (MTEMultiBlockBase) entry.machine()
                .newMetaEntity(null);
            for (Modifier<?> modifier : machine.getModifiersForInspection()) {
                modifier.setToMax();
            }
            int modes = machine.getMachineModes()
                .size();
            for (int mode = 0; mode < Math.max(1, modes); mode++) {
                if (!entry.spec()
                    .supportsMode(mode)) continue;
                if (modes > 1) machine.setMachineMode(mode);
                for (GTRecipe recipe : sample(modes > 1 ? machine.getRecipeMapForMode(mode) : machine.getRecipeMap())) {
                    for (List<ProcessingSpec.EnergyHatch> hatches : ENERGY_SAMPLES) {
                        machine.setEnergyHatchesForInspection(hatches);
                        ProcessingSpec.Run own = machine.calculateForInspection(recipe);
                        if (own == null) return null;
                        ProcessingSpec.Run fromSpec = entry.spec()
                            .calculate(recipe, machine.getCurrentProcessingSpecInputs());
                        if (!sameRun(own, fromSpec)) {
                            return "mode " + mode + ", " + hatches + ": own " + own + ", spec " + fromSpec;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static boolean sameRun(ProcessingSpec.Run a, ProcessingSpec.Run b) {
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
            && a.startupEu() == b.startupEu();
    }

    /** Empty for a machine that cannot be inspected without a world. */
    private static Set<ProcessingSpec.Quantity> undeclaredCustom(Entry entry) {
        EnumSet<ProcessingSpec.Quantity> differ = EnumSet.noneOf(ProcessingSpec.Quantity.class);
        try {
            MTEMultiBlockBase machine = (MTEMultiBlockBase) entry.machine()
                .newMetaEntity(null);
            for (Modifier<?> modifier : machine.getModifiersForInspection()) {
                modifier.setToMax();
            }
            int modes = machine.getMachineModes()
                .size();
            for (int mode = 0; mode < Math.max(1, modes); mode++) {
                if (!entry.spec()
                    .supportsMode(mode)) continue;
                if (modes > 1) machine.setMachineMode(mode);
                for (GTRecipe recipe : sample(modes > 1 ? machine.getRecipeMapForMode(mode) : machine.getRecipeMap())) {
                    OverclockCalculator own = machine.createOverclockCalculatorForInspection(recipe);
                    if (own == null) return Collections.emptySet();
                    ProcessingLogic specLogic = new ProcessingLogic().setAmperageOC(true)
                        .applySpec(entry.spec(), machine::getCurrentProcessingSpecInputs);
                    OverclockCalculator fromSpec = specLogic.createOverclockCalculatorForInspection(recipe);
                    differ.addAll(differences(own, fromSpec));
                    if (machine.getProcessingLogic()
                        .getResolvedMaxParallel() != specLogic.getResolvedMaxParallel()) {
                        differ.add(ProcessingSpec.Quantity.PARALLEL);
                    }
                }
            }
        } catch (RuntimeException e) {
            return Collections.emptySet();
        }
        Set<ProcessingSpec.Quantity> declared = entry.spec()
            .getAlsoCustom();
        differ.removeAll(declared);
        if (declared.contains(ProcessingSpec.Quantity.EU_MODIFIER_NOT_LIMITING_PARALLEL)) {
            differ.remove(ProcessingSpec.Quantity.EU_MODIFIER);
        }
        return differ;
    }

    /** One regular hatch, two, and a 16 A hatch, at three tiers. */
    private static List<List<ProcessingSpec.EnergyHatch>> energySamples() {
        List<List<ProcessingSpec.EnergyHatch>> samples = new ArrayList<>();
        for (int tier : new int[] { VoltageIndex.HV, VoltageIndex.LuV, VoltageIndex.UHV }) {
            ProcessingSpec.EnergyHatch regular = ProcessingSpec.EnergyHatch.regular(tier);
            samples.add(List.of(regular));
            samples.add(List.of(regular, regular));
            samples.add(List.of(ProcessingSpec.EnergyHatch.exotic(tier, 16)));
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

    /** Leaves out hatch voltage and amperage, and heat unless either calculator uses it. */
    private static Set<ProcessingSpec.Quantity> differences(OverclockCalculator a, OverclockCalculator b) {
        EnumSet<ProcessingSpec.Quantity> differ = EnumSet.noneOf(ProcessingSpec.Quantity.class);
        if (a.getParallel() != b.getParallel()) differ.add(ProcessingSpec.Quantity.PARALLEL);
        if (a.getDurationModifier() != b.getDurationModifier()) differ.add(ProcessingSpec.Quantity.DURATION);
        if (a.getEUtDiscount() != b.getEUtDiscount()) differ.add(ProcessingSpec.Quantity.EU_MODIFIER);
        if (a.isNoOverclock() != b.isNoOverclock() || a.getDurationDecreasePerOC() != b.getDurationDecreasePerOC()
            || a.getEUtIncreasePerOC() != b.getEUtIncreasePerOC()
            || a.isLaserOC() != b.isLaserOC()
            || a.getMaxOverclocks() != b.getMaxOverclocks()
            || a.getMaxRegularOverclocks() != b.getMaxRegularOverclocks()) {
            differ.add(ProcessingSpec.Quantity.OVERCLOCK);
        }
        if (a.getMaxTierSkips() != b.getMaxTierSkips()) differ.add(ProcessingSpec.Quantity.TIER_SKIPS);
        boolean heatCounts = a.isHeatOC() || a.isHeatDiscount() || b.isHeatOC() || b.isHeatDiscount();
        if (heatCounts && (a.isHeatOC() != b.isHeatOC() || a.isHeatDiscount() != b.isHeatDiscount()
            || a.getMachineHeat() != b.getMachineHeat()
            || a.getRecipeHeat() != b.getRecipeHeat()
            || a.getHeatDiscountMultiplier() != b.getHeatDiscountMultiplier())) {
            differ.add(ProcessingSpec.Quantity.HEAT);
        }
        if (a.getRecipeEUt() != b.getRecipeEUt() || a.getRecipeDuration() != b.getRecipeDuration()) {
            differ.add(ProcessingSpec.Quantity.RECIPE_OVERRIDE);
        }
        return differ;
    }
}
