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
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.structure.StructureParameter;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.OverclockCalculator;

/** The registered multiblocks that declare a {@link ProcessingSpec}, for external tools such as factory planners. */
public final class ProcessingSpecs {

    /** Recipes per recipe map that {@link #problems()} compares calculators on, spread over their heat and EU/t. */
    private static final int SAMPLE_RECIPES = 8;

    /**
     * @param machine    The registered prototype; use {@link MTEMultiBlockBase#newMetaEntity} before changing anything
     * @param parameters Its structure parameters, for their ranges; set values on a copy only
     */
    public record Entry(@Nonnull MTEMultiBlockBase machine, @Nonnull ProcessingSpec spec,
        @Nonnull List<StructureParameter> parameters) {}

    private ProcessingSpecs() {}

    /** Every registered multiblock with a spec, leaving out deprecated structures. */
    @Nonnull
    public static Stream<Entry> all() {
        return Arrays.stream(GregTechAPI.METATILEENTITIES)
            .filter(MTEMultiBlockBase.class::isInstance)
            .map(MTEMultiBlockBase.class::cast)
            .filter(machine -> !machine.isStructureDeprecated())
            .map(machine -> {
                ProcessingSpec spec = machine.getProcessingSpec();
                return spec == null ? null : new Entry(machine, spec, machine.getStructureParametersForInspection());
            })
            .filter(Objects::nonNull);
    }

    /**
     * @throws IllegalStateException listing {@link #problems()}, if there are any
     */
    public static void check() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Processing spec problems:\n  " + String.join("\n  ", problems));
        }
    }

    /**
     * Each machine whose tooltip leaves out a number its spec sets, and each machine whose own code gives its overclock
     * calculator a number its spec does not, without saying so with {@link ProcessingSpec.Builder#alsoCustom}. The
     * second compares the calculator the machine builds with the one its spec alone builds, on a copy of the machine
     * with every structure parameter at its maximum, in every mode, for a sample of the mode's recipes.
     */
    @Nonnull
    public static List<String> problems() {
        List<String> problems = new ArrayList<>();
        all().forEach(entry -> problems.addAll(problems(entry)));
        return problems;
    }

    /** The {@link #problems()} of one machine. */
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
                name + " gives its overclock calculator its own "
                    + custom
                    + "; declare them in its spec or mark it alsoCustom("
                    + custom
                    + ")");
        }
        return problems;
    }

    /**
     * The quantities where the machine's calculator differs from its spec's and the spec does not mark them
     * {@link ProcessingSpec.Builder#alsoCustom}. Empty for a machine that cannot be inspected without a world.
     */
    private static Set<ProcessingSpec.Quantity> undeclaredCustom(Entry entry) {
        EnumSet<ProcessingSpec.Quantity> differ = EnumSet.noneOf(ProcessingSpec.Quantity.class);
        try {
            MTEMultiBlockBase machine = (MTEMultiBlockBase) entry.machine()
                .newMetaEntity(null);
            for (StructureParameter parameter : machine.getStructureParametersForInspection()) {
                parameter.set(parameter.max);
            }
            int modes = machine.getMachineModes()
                .size();
            for (int mode = 0; mode < Math.max(1, modes); mode++) {
                if (modes > 1) machine.setMachineMode(mode);
                for (GTRecipe recipe : sample(modes > 1 ? machine.getRecipeMapForMode(mode) : machine.getRecipeMap())) {
                    OverclockCalculator own = machine.createOverclockCalculatorForInspection(recipe);
                    if (own == null) return Collections.emptySet();
                    OverclockCalculator fromSpec = new ProcessingLogic().setAmperageOC(true)
                        .applySpec(entry.spec(), machine::getCurrentProcessingSpecInputs)
                        .createOverclockCalculatorForInspection(recipe);
                    differ.addAll(differences(own, fromSpec));
                }
            }
        } catch (RuntimeException e) {
            return Collections.emptySet();
        }
        Set<ProcessingSpec.Quantity> declared = entry.spec()
            .getAlsoCustom();
        differ.removeAll(declared);
        differ.removeAll(
            entry.spec()
                .getBestCase());
        if (declared.contains(ProcessingSpec.Quantity.EU_MODIFIER_NOT_LIMITING_PARALLEL)) {
            differ.remove(ProcessingSpec.Quantity.EU_MODIFIER);
        }
        return differ;
    }

    /** Up to {@link #SAMPLE_RECIPES} of the map's recipes, from the lowest heat and EU/t to the highest. */
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

    /**
     * The quantities whose calculator inputs differ. The energy hatches' voltage and amperage are not compared, nor
     * heat values while neither calculator overclocks or discounts by heat.
     */
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
