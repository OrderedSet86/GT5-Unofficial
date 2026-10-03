package gregtech.api.logic;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import gregtech.api.enums.GTValues;
import gregtech.api.interfaces.tileentity.IRecipeLockable;
import gregtech.api.interfaces.tileentity.IVoidable;
import gregtech.api.objects.GTDualInputPattern;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.api.recipe.check.CheckRecipeResultRegistry;
import gregtech.api.recipe.check.SingleRecipeCheck;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.api.util.OverclockCalculator;
import gregtech.api.util.ParallelHelper;
import gregtech.common.tileentities.machines.IDualInputInventoryWithPattern;

/**
 * Logic class to calculate result of recipe check from inputs, based on recipemap.
 */
@SuppressWarnings({ "unused", "UnusedReturnValue" })
public class ProcessingLogic {

    // Traits
    protected IVoidable machine;
    protected IRecipeLockable recipeLockableMachine;
    protected boolean isRecipeLocked;
    protected ItemStack[] inputItems;
    protected FluidStack[] inputFluids;
    protected ItemStack specialSlotItem;
    protected int maxParallel = 1;
    protected Supplier<Integer> maxParallelSupplier;
    protected Supplier<Double> euModSupplier;
    protected Supplier<Double> speedBoostSupplier;
    protected int batchSize = 1;
    protected Supplier<RecipeMap<?>> recipeMapSupplier;
    protected double euModifier = 1.0;
    protected double speedBoost = 1.0;
    protected long availableVoltage;
    protected long availableAmperage;
    protected int maxTierSkips = 1;
    protected boolean protectItems;
    protected boolean protectFluids;
    protected double overClockTimeReduction = 2.0;
    protected double overClockPowerIncrease = 4.0;
    protected boolean amperageOC = true;
    protected boolean recipeCaching = true;
    protected ProcessingSpec spec;
    protected ProcessingSpec.Inputs specInputs;
    /** As {@link ProcessingSpec.Power#unlimited}. */
    protected boolean unlimitedEnergy;

    // Calculated results
    protected ItemStack[] outputItems;
    protected FluidStack[] outputFluids;
    protected long calculatedEut;
    protected int duration;
    protected int calculatedParallels = 0;

    // Cache
    protected RecipeMap<?> lastRecipeMap;
    protected GTRecipe lastRecipe;

    /**
     * The {@link IDualInputInventoryWithPattern} that has any possible recipe found in the last call of
     * {@link #tryCachePossibleRecipesFromPattern(IDualInputInventoryWithPattern)}.
     */
    protected IDualInputInventoryWithPattern activeDualInv;
    /**
     * The cache keyed by the {@link IDualInputInventoryWithPattern}, storing the possible recipes of the inv.
     * <p>
     * The entries can be removed by {@link #removeInventoryRecipeCache(IDualInputInventoryWithPattern)}.
     * <p>
     * It will also be fully cleared when the {@link #getCurrentRecipeMap()} is not same to the last.
     */
    protected WeakHashMap<IDualInputInventoryWithPattern, Set<GTRecipe>> dualInvWithPatternToRecipeCache = new WeakHashMap<>();

    public ProcessingLogic() {}

    // region Setters

    /**
     * Sets machine used for void protection logic.
     */
    public ProcessingLogic setMachine(IVoidable machine) {
        this.machine = machine;
        return this;
    }

    /**
     * Enables single recipe locking mode.
     */
    public ProcessingLogic setRecipeLocking(IRecipeLockable recipeLockableMachine, boolean isRecipeLocked) {
        this.recipeLockableMachine = recipeLockableMachine;
        this.isRecipeLocked = isRecipeLocked;
        return this;
    }

    @Nonnull
    public ProcessingLogic setInputItems(ItemStack... itemInputs) {
        this.inputItems = itemInputs;
        return this;
    }

    @Nonnull
    public ProcessingLogic setInputItems(List<ItemStack> itemInputs) {
        this.inputItems = itemInputs.toArray(new ItemStack[0]);
        return this;
    }

    @Nonnull
    public ProcessingLogic setInputFluids(FluidStack... fluidInputs) {
        this.inputFluids = fluidInputs;
        return this;
    }

    @Nonnull
    public ProcessingLogic setInputFluids(List<FluidStack> fluidInputs) {
        this.inputFluids = fluidInputs.toArray(new FluidStack[0]);
        return this;
    }

    public ProcessingLogic setSpecialSlotItem(ItemStack specialSlotItem) {
        this.specialSlotItem = specialSlotItem;
        return this;
    }

    /**
     * Try to cache the possible recipes from the pattern.
     * <p>
     * If the inventory can be cached, and any possible recipe is found, {@link #activeDualInv the active inv} will be
     * set to the given inventory.
     *
     * @return {@code true} if the inv shouldn't be cached, or there is already a cached recipe sets, or the recipes are
     *         cached successfully. {@code false} if there is no recipe found.
     */
    public boolean tryCachePossibleRecipesFromPattern(IDualInputInventoryWithPattern inv) {
        // call getCurrentRecipeMap here to clear dualInv recipe cache if recipe map changes
        RecipeMap<?> recipeMap = getCurrentRecipeMap();

        if (!inv.shouldBeCached()) {
            return true;
        }

        // check existing caches
        if (dualInvWithPatternToRecipeCache.containsKey(inv)) {
            activeDualInv = inv;
            return true;
        }

        // get recipes from the pattern
        GTDualInputPattern inputs = inv.getPatternInputs();
        setInputItems(prepareCatalyst(inputs.inputItems));
        setInputFluids(inputs.inputFluid);
        Set<GTRecipe> recipes = findRecipeMatches(recipeMap).collect(Collectors.toCollection(LinkedHashSet::new));

        // reset the status
        setInputItems();
        setInputFluids();

        if (!recipes.isEmpty()) {
            dualInvWithPatternToRecipeCache.put(inv, recipes);
            activeDualInv = inv;
            return true;
        } else {
            return false;
        }
    }

    public void removeInventoryRecipeCache(IDualInputInventoryWithPattern inv) {
        dualInvWithPatternToRecipeCache.remove(inv);
    }

    /**
     * Sets max amount of parallel.
     */
    public ProcessingLogic setMaxParallel(int maxParallel) {
        this.maxParallel = maxParallel;
        return this;
    }

    /**
     * Sets method to get max amount of parallel.
     */
    public ProcessingLogic setMaxParallelSupplier(Supplier<Integer> supplier) {
        this.maxParallelSupplier = supplier;
        return this;
    }

    /**
     * Sets batch size for batch mode.
     */
    public ProcessingLogic setBatchSize(int size) {
        this.batchSize = size;
        return this;
    }

    public ProcessingLogic setRecipeMap(RecipeMap<?> recipeMap) {
        return setRecipeMapSupplier(() -> recipeMap);
    }

    public ProcessingLogic setRecipeMapSupplier(Supplier<RecipeMap<?>> supplier) {
        this.recipeMapSupplier = supplier;
        return this;
    }

    public ProcessingLogic setEuModifier(double modifier) {
        this.euModifier = modifier;
        return this;
    }

    public ProcessingLogic setEuModifierSupplier(Supplier<Double> supplier) {
        this.euModSupplier = supplier;
        return this;
    }

    public ProcessingLogic setSpeedBonus(double speedModifier) {
        this.speedBoost = speedModifier;
        return this;
    }

    public ProcessingLogic setSpeedBonusSupplier(Supplier<Double> supplier) {
        this.speedBoostSupplier = supplier;
        return this;
    }

    /**
     * Sets voltage of the machine. It doesn't need to be actual voltage (excluding amperage) of the machine; For
     * example, most of the multiblock machines set maximum possible input power (including amperage) as voltage and 1
     * as amperage. That way recipemap search will be executed with overclocked voltage.
     */
    public ProcessingLogic setAvailableVoltage(long voltage) {
        this.availableVoltage = voltage;
        return this;
    }

    /**
     * Sets amperage of the machine. This amperage doesn't involve in EU/t when searching recipemap. Useful for
     * preventing tier skip but still considering amperage for parallel.
     */
    public ProcessingLogic setAvailableAmperage(long amperage) {
        this.availableAmperage = amperage;
        return this;
    }

    /**
     * Sets the max amount of tier skips, which is how many voltage tiers above the input voltage a recipe is valid. For
     * unlimited tier skips, use {@link #setUnlimitedTierSkips()}
     */
    public ProcessingLogic setMaxTierSkips(int tierSkips) {
        this.maxTierSkips = tierSkips;
        return this;
    }

    public ProcessingLogic setUnlimitedTierSkips() {
        this.maxTierSkips = Integer.MAX_VALUE;
        return this;
    }

    public ProcessingLogic setVoidProtection(boolean protectItems, boolean protectFluids) {
        this.protectItems = protectItems;
        this.protectFluids = protectFluids;
        return this;
    }

    public ProcessingLogic setOverclock(double timeReduction, double powerIncrease) {
        this.overClockTimeReduction = timeReduction;
        this.overClockPowerIncrease = powerIncrease;
        return this;
    }

    /**
     * Sets overclock ratio to 4/4.
     */
    public ProcessingLogic enablePerfectOverclock() {
        return this.setOverclock(4.0, 4.0);
    }

    /**
     * Sets whether the multi should use amperage to OC or not.
     */
    public ProcessingLogic setAmperageOC(boolean amperageOC) {
        this.amperageOC = amperageOC;
        return this;
    }

    /** Sets the spec's numbers at these inputs, so call it at every recipe check. */
    public ProcessingLogic applySpec(@Nonnull ProcessingSpec spec, @Nonnull ProcessingSpec.Inputs inputs) {
        this.spec = spec;
        this.specInputs = inputs;
        ProcessingSpec.Power power = spec.getPower(inputs);
        setAvailableVoltage(power.voltage());
        setAvailableAmperage(power.amperage());
        setAmperageOC(power.amperageOverclock());
        this.unlimitedEnergy = power.unlimited();
        if (spec.sets(ProcessingSpec.Quantity.PARALLEL)) {
            setMaxParallelSupplier(() -> spec.getMaxParallel(inputs));
        }
        if (spec.sets(ProcessingSpec.Quantity.DURATION)) {
            setSpeedBonusSupplier(() -> spec.getDurationMultiplier(inputs));
        }
        if (spec.sets(ProcessingSpec.Quantity.EU_MODIFIER)) {
            setEuModifierSupplier(() -> spec.getEuModifier(inputs));
        }
        spec.getOverclock(inputs)
            .ifPresent(rule -> {
                if (rule instanceof ProcessingSpec.OverclockRule.Ratio ratio) {
                    setOverclock(ratio.durationDivisor(), ratio.euMultiplier());
                }
            });
        spec.getMaxTierSkips()
            .ifPresent(this::setMaxTierSkips);
        return this;
    }

    /**
     * Disable caching of matched recipes.
     */
    public ProcessingLogic noRecipeCaching() {
        this.recipeCaching = false;
        return this;
    }

    // endregion

    // region Overwrite calculated result

    /**
     * Overwrites calculated item output.
     */
    public ProcessingLogic overwriteOutputItems(ItemStack... itemOutputs) {
        this.outputItems = itemOutputs;
        return this;
    }

    /**
     * Overwrites calculated fluid output.
     */
    public ProcessingLogic overwriteOutputFluids(FluidStack... fluidOutputs) {
        this.outputFluids = fluidOutputs;
        return this;
    }

    /**
     * Overwrites calculated EU/t.
     */
    public ProcessingLogic overwriteCalculatedEut(long calculatedEut) {
        this.calculatedEut = calculatedEut;
        return this;
    }

    /**
     * Overwrites calculated duration.
     */
    public ProcessingLogic overwriteCalculatedDuration(int duration) {
        this.duration = duration;
        return this;
    }

    // endregion

    /**
     * Clears calculated results and provided machine inputs to prepare for the next machine operation.
     */
    public ProcessingLogic clear() {
        this.inputItems = null;
        this.inputFluids = null;
        this.specialSlotItem = null;
        this.outputItems = null;
        this.outputFluids = null;
        this.calculatedEut = 0;
        this.duration = 0;
        this.calculatedParallels = 0;
        this.activeDualInv = null;
        return this;
    }

    // region Logic

    /**
     * Refreshes recipemap to use. Remember to call this before {@link #process} to make sure correct recipemap is used.
     *
     * @return Recipemap to use now
     */
    protected RecipeMap<?> getCurrentRecipeMap() {
        RecipeMap<?> recipeMap;
        if (recipeMapSupplier == null) {
            recipeMap = null;
        } else {
            recipeMap = recipeMapSupplier.get();
        }
        if (lastRecipeMap != recipeMap) {
            if (lastRecipeMap != null) {
                dualInvWithPatternToRecipeCache.clear();
            }
            lastRecipe = null;
            lastRecipeMap = recipeMap;
        }
        return recipeMap;
    }

    /**
     * Executes the recipe check: Find recipe from recipemap, Calculate parallel, overclock and outputs.
     */
    @Nonnull
    public CheckRecipeResult process() {
        RecipeMap<?> recipeMap = getCurrentRecipeMap();

        resolveModifierSuppliers();

        if (inputItems == null) {
            inputItems = GTValues.emptyItemStackArray;
        }
        if (inputFluids == null) {
            inputFluids = GTValues.emptyFluidStackArray;
        }
        inputItems = prepareCatalyst(inputItems);
        if (activeDualInv != null) {
            Set<GTRecipe> matchedRecipes = dualInvWithPatternToRecipeCache.get(activeDualInv);
            for (GTRecipe matchedRecipe : matchedRecipes) {
                if (matchedRecipe.maxParallelCalculatedByInputs(1, inputFluids, inputItems) == 1) {
                    CalculationResult foundResult = validateAndCalculateRecipe(matchedRecipe);
                    return foundResult.checkRecipeResult;
                }
            }

            // recipe cache does not match, this might be caused by changes of return value of
            // prepareCatalyst(ItemStack[]) or changes of Encoded Patterns in the CRIB
            // so clear the cache and proceed, the new cache will be generated in the next recipe check
            dualInvWithPatternToRecipeCache.remove(activeDualInv);
            activeDualInv = null;
        }

        if (isRecipeLocked && recipeLockableMachine != null && recipeLockableMachine.getSingleRecipeCheck() != null) {
            // Recipe checker is already built, we'll use it
            SingleRecipeCheck singleRecipeCheck = recipeLockableMachine.getSingleRecipeCheck();
            // Validate recipe here, otherwise machine will show "not enough output space"
            // even if recipe cannot be found
            if (singleRecipeCheck.checkRecipeInputs(false, 1, inputItems, inputFluids) == 0) {
                return CheckRecipeResultRegistry.NO_RECIPE;
            }

            return validateAndCalculateRecipe(
                recipeLockableMachine.getSingleRecipeCheck()
                    .getRecipe()).checkRecipeResult;
        }
        Stream<GTRecipe> matchedRecipes = findRecipeMatches(recipeMap);
        Iterable<GTRecipe> recipeIterable = matchedRecipes::iterator;
        CheckRecipeResult checkRecipeResult = CheckRecipeResultRegistry.NO_RECIPE;
        for (GTRecipe matchedRecipe : recipeIterable) {
            CalculationResult foundResult = validateAndCalculateRecipe(matchedRecipe);
            if (foundResult.successfullyConsumedInputs) {
                // Successfully found and set recipe, so return it
                return foundResult.checkRecipeResult;
            }
            if (foundResult.checkRecipeResult != CheckRecipeResultRegistry.NO_RECIPE) {
                // Recipe failed in interesting way, so remember that and continue searching
                checkRecipeResult = foundResult.checkRecipeResult;
            }
        }
        return checkRecipeResult;
    }

    protected void resolveModifierSuppliers() {
        if (maxParallelSupplier != null) {
            maxParallel = maxParallelSupplier.get();
        }

        if (euModSupplier != null) {
            euModifier = euModSupplier.get();
        }

        if (speedBoostSupplier != null) {
            speedBoost = speedBoostSupplier.get();
        }
    }

    /**
     * Checks if supplied recipe is valid for process. This involves voltage check, output full check. If successful,
     * additionally performs input consumption, output calculation with parallel, and overclock calculation.
     *
     * @param recipe The recipe which will be checked and processed
     */
    @Nonnull
    private CalculationResult validateAndCalculateRecipe(@Nonnull GTRecipe found) {
        GTRecipe recipe = specRecipe(found);
        CheckRecipeResult result = checkSpecRequirements(recipe);
        if (result.wasSuccessful()) result = validateRecipe(recipe);
        if (!result.wasSuccessful()) {
            return CalculationResult.ofFailure(result);
        }

        ParallelHelper helper = createParallelHelper(recipe);
        OverclockCalculator calculator = createOverclockCalculator(recipe);
        helper.setCalculator(calculator);
        helper.build();

        if (!helper.getResult()
            .wasSuccessful()) {
            return CalculationResult.ofFailure(helper.getResult());
        }

        return CalculationResult.ofSuccess(applyRecipe(recipe, helper, calculator, result));
    }

    /**
     * Check has been succeeded, so it applies the recipe and calculated parameters. At this point, inputs have been
     * already consumed.
     */
    @Nonnull
    protected CheckRecipeResult applyRecipe(@Nonnull GTRecipe recipe, @Nonnull ParallelHelper helper,
        @Nonnull OverclockCalculator calculator, @Nonnull CheckRecipeResult result) {
        if (recipe.mCanBeBuffered) {
            lastRecipe = recipe;
        } else {
            lastRecipe = null;
        }
        calculatedParallels = helper.getCurrentParallel();

        if (calculator.getConsumption() == Long.MAX_VALUE) {
            return CheckRecipeResultRegistry.POWER_OVERFLOW;
        }
        if (calculator.getDuration() == Integer.MAX_VALUE) {
            return CheckRecipeResultRegistry.DURATION_OVERFLOW;
        }

        calculatedEut = cappedConsumption(calculator);

        double finalDuration = calculateDuration(recipe, helper, calculator);
        if (finalDuration >= Integer.MAX_VALUE) {
            return CheckRecipeResultRegistry.DURATION_OVERFLOW;
        }
        duration = (int) finalDuration;

        CheckRecipeResult hookResult = onRecipeStart(recipe);
        if (!hookResult.wasSuccessful()) {
            return hookResult;
        }

        outputItems = helper.getItemOutputs();
        outputFluids = helper.getFluidOutputs();

        return result;
    }

    /**
     * Override to tweak final duration that will be set as a result of this logic class.
     */
    protected double calculateDuration(@Nonnull GTRecipe recipe, @Nonnull ParallelHelper helper,
        @Nonnull OverclockCalculator calculator) {
        return calculator.getDuration() * helper.getDurationMultiplierDouble();
    }

    /**
     * Finds a list of matched recipes. At this point no additional check to the matched recipe has been done.
     * <p>
     * Override {@link #validateRecipe} to have custom check.
     * <p>
     * Override this method if it doesn't work with normal recipemaps.
     */
    @Nonnull
    protected Stream<GTRecipe> findRecipeMatches(@Nullable RecipeMap<?> map) {
        if (map == null) {
            return Stream.empty();
        }
        return map.findRecipeQuery()
            .caching(recipeCaching)
            .items(inputItems)
            .fluids(inputFluids)
            .specialSlot(specialSlotItem)
            .cachedRecipe(lastRecipe)
            .findAll();
    }

    /**
     * Override to do additional check for found recipe if needed.
     */
    @Nonnull
    protected CheckRecipeResult validateRecipe(@Nonnull GTRecipe recipe) {
        return CheckRecipeResultRegistry.SUCCESSFUL;
    }

    /** Override to skip the spec's requirements, such as when resuming a recipe after loading. */
    @Nonnull
    protected CheckRecipeResult checkSpecRequirements(@Nonnull GTRecipe recipe) {
        return spec == null ? CheckRecipeResultRegistry.SUCCESSFUL : spec.check(recipe, specInputs);
    }

    /**
     * Override to tweak parallel logic if needed.
     */
    @Nonnull
    protected ParallelHelper createParallelHelper(@Nonnull GTRecipe recipe) {
        return new ParallelHelper().setRecipe(recipe)
            .setItemInputs(inputItems)
            .setFluidInputs(inputFluids)
            .setAvailableEUt(availableEUt())
            .setMachine(machine, protectItems, protectFluids)
            .setRecipeLocked(recipeLockableMachine, isRecipeLocked)
            .setMaxParallel(maxParallelFor(recipe))
            .setEUtModifier(euModifier)
            .enableBatchMode(batchSize)
            .setConsumption(true)
            .setOutputCalculation(true);
    }

    private long cappedConsumption(OverclockCalculator calculator) {
        long consumption = calculator.getConsumption();
        return spec == null ? consumption : Math.min(spec.getMaxEuPerTick(specInputs), consumption);
    }

    private long availableEUt() {
        return unlimitedEnergy ? Long.MAX_VALUE : availableVoltage * availableAmperage;
    }

    /**
     * The recipe as the spec runs it: a copy at the spec's fixed cost, or with its EU/t multiplied, else the recipe
     * itself.
     */
    @Nonnull
    protected GTRecipe specRecipe(@Nonnull GTRecipe recipe) {
        if (spec == null) return recipe;
        Optional<ProcessingSpec.RecipeOverride> override = spec.getRecipeOverride();
        double euMultiplier = spec.getRecipeEuMultiplier(specInputs);
        if (override.isEmpty() && euMultiplier == 1) return recipe;
        GTRecipe copy = recipe.copy();
        // a cached copy would be multiplied again at the next check
        copy.mCanBeBuffered = false;
        override.ifPresent(o -> {
            copy.mEUt = GTUtility.safeInt(o.eut(), 0);
            copy.mDuration = o.duration();
        });
        if (euMultiplier != 1) copy.mEUt = (int) Math.min((long) (copy.mEUt * euMultiplier), Integer.MAX_VALUE);
        return copy;
    }

    /** The spec's parallel for this recipe if it depends on the recipe, else {@link #maxParallel}. */
    protected int maxParallelFor(@Nonnull GTRecipe recipe) {
        return spec != null && spec.readsRecipe(ProcessingSpec.Quantity.PARALLEL)
            ? spec.getMaxParallel(specInputs, recipe)
            : maxParallel;
    }

    /**
     * Override to tweak overclock logic if needed.
     */
    @Nonnull
    protected OverclockCalculator createOverclockCalculator(@Nonnull GTRecipe recipe) {
        double euModifierNotLimitingParallel = spec == null ? 1 : spec.getEuModifierNotLimitingParallel(specInputs);
        int duration = spec == null ? recipe.mDuration
            : spec.getRecipeDuration(specInputs, recipe)
                .orElse(recipe.mDuration);
        if (spec != null && spec.isNoOverclock()) {
            return OverclockCalculator.ofNoOverclock(recipe.mEUt, duration)
                .setDurationModifier(speedBoost)
                .setEUtDiscount(euModifier * euModifierNotLimitingParallel);
        }
        OverclockCalculator calculator = new OverclockCalculator().setRecipeEUt(recipe.mEUt)
            .setAmperage(availableAmperage)
            .setEUt(availableVoltage)
            .setMaxTierSkips(maxTierSkips)
            .setDuration(duration)
            .setDurationModifier(speedBoost)
            .setEUtDiscount(euModifier * euModifierNotLimitingParallel)
            .setAmperageOC(amperageOC)
            .setDurationDecreasePerOC(overClockTimeReduction)
            .setEUtIncreasePerOC(overClockPowerIncrease);
        if (spec != null) spec.getMaxOverclocks(specInputs, recipe)
            .ifPresent(calculator::setMaxOverclocks);
        if (spec != null) spec.getHeat()
            .ifPresent(
                heat -> calculator.setMachineHeat(heat.getMachineHeat(specInputs))
                    .setRecipeHeat(heat.getRecipeHeat(recipe))
                    .setHeatOC(heat.isOverclocking())
                    .setHeatDiscount(heat.isDiscounting()));
        if (unlimitedEnergy) calculator.setAmperage(1)
            .setEUt(Long.MAX_VALUE);
        return calculator;
    }

    /**
     * For planners. Machines override {@link #createOverclockCalculator}, and an override can change the machine's
     * state
     * while building the calculator.
     */
    @Nonnull
    public final OverclockCalculator createOverclockCalculatorForInspection(@Nonnull GTRecipe recipe) {
        resolveModifierSuppliers();
        return createOverclockCalculator(recipe);
    }

    /**
     * For planners: {@link #process()}'s parallel and overclock calculation, with unlimited inputs and output space.
     */
    @Nonnull
    public final ProcessingSpec.Run calculateForInspection(@Nonnull GTRecipe recipe) {
        resolveModifierSuppliers();
        GTRecipe run = specRecipe(recipe);
        if (spec != null) {
            CheckRecipeResult check = spec.check(run, specInputs);
            if (check.wasSuccessful()) check = spec.checkToStart(run, specInputs);
            if (!check.wasSuccessful()) return ProcessingSpec.Run.failed(check);
        }
        OverclockCalculator calculator = createOverclockCalculator(run);
        ParallelHelper helper = new ParallelHelper().setRecipe(run)
            .setAvailableEUt(availableEUt())
            .setMaxParallel(maxParallelFor(run))
            .setEUtModifier(euModifier)
            .setCalculator(calculator)
            .setConsumption(false)
            .setMaxParallelCalculator((r, max, fluids, items) -> max)
            .setInputConsumer((r, amount, fluids, items) -> {})
            .build();
        if (!helper.getResult()
            .wasSuccessful()) return ProcessingSpec.Run.failed(helper.getResult());
        return ProcessingSpec.Run.builder(helper.getResult())
            .parallel(helper.getCurrentParallel())
            .overclocks(calculator.getPerformedOverclocks())
            .ticks(calculator.getDuration())
            .euPerTick(cappedConsumption(calculator))
            .startupEu(spec == null ? 0 : spec.getStartupEu(specInputs, run))
            .euPerRun(spec == null ? BigInteger.ZERO : spec.getEuPerRun(specInputs, run))
            .euGeneratedPerRun(spec == null ? BigInteger.ZERO : spec.getEuGeneratedPerRun(specInputs, run))
            .successChance(spec == null ? 1 : spec.getSuccessChance(specInputs, run))
            .outputYield(spec == null ? 1 : spec.getOutputYield(specInputs, run))
            .build();
    }

    /**
     * Override to perform additional logic when recipe starts.
     * <p>
     * This is called when the recipe processing logic has finished all checks, consumed all inputs, but has not yet set
     * the outputs to be produced. Returning a result other than SUCCESSFUL will void all inputs!
     */
    @Nonnull
    protected CheckRecipeResult onRecipeStart(@Nonnull GTRecipe recipe) {
        return CheckRecipeResultRegistry.SUCCESSFUL;
    }

    /**
     * Add some catalyst items into input items array, like Milling Ball or Chemical Plant Catalyst.
     * Do not modify, return a new array if something is changed.
     */
    protected ItemStack[] prepareCatalyst(ItemStack[] inputs) {
        return inputs;
    }

    // endregion

    // region Getters

    public ItemStack[] getOutputItems() {
        return outputItems;
    }

    public FluidStack[] getOutputFluids() {
        return outputFluids;
    }

    public int getDuration() {
        return duration;
    }

    public long getCalculatedEut() {
        return calculatedEut;
    }

    public int getCurrentParallels() {
        return calculatedParallels;
    }

    public long getMaxAllowedRecipeEUt() {
        return OverclockCalculator.getMaxAllowedRecipeEUt(availableVoltage, maxTierSkips);
    }

    /** Not getMaxParallel(): many machines declare one, and anonymous subclasses would shadow it. */
    public int getResolvedMaxParallel() {
        return maxParallel;
    }

    // endregion

    /**
     * Represents the status of check recipe calculation. {@link #successfullyConsumedInputs} does not necessarily mean
     * {@link #checkRecipeResult} being successful, when duration or power is overflowed. Being failure means recipe
     * cannot meet requirements and recipe search should be continued if possible.
     */
    protected final static class CalculationResult {

        public final boolean successfullyConsumedInputs;
        public final CheckRecipeResult checkRecipeResult;

        public static CalculationResult ofSuccess(CheckRecipeResult checkRecipeResult) {
            return new CalculationResult(true, checkRecipeResult);
        }

        public static CalculationResult ofFailure(CheckRecipeResult checkRecipeResult) {
            return new CalculationResult(false, checkRecipeResult);
        }

        private CalculationResult(boolean successfullyConsumedInputs, CheckRecipeResult checkRecipeResult) {
            this.successfullyConsumedInputs = successfullyConsumedInputs;
            this.checkRecipeResult = checkRecipeResult;
        }
    }
}
