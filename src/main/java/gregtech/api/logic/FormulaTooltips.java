package gregtech.api.logic;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import gregtech.api.util.MultiblockTooltipBuilder;

/** The tooltip line each {@link Formula} gets, by the quantity it sets. */
final class FormulaTooltips {

    private FormulaTooltips() {}

    /** @return Null if the formula cannot describe itself, such as {@link Formula.Custom} */
    @Nullable
    static Consumer<MultiblockTooltipBuilder> lines(ProcessingSpec.Quantity quantity, Formula formula) {
        return switch (quantity) {
            case PARALLEL -> parallel(formula);
            case DURATION -> speed(formula);
            case EU_MODIFIER -> euModifier(formula);
            case RECIPE_EU_MULTIPLIER -> formula instanceof Formula.Constant multiplier
                ? tt -> tt.addRecipeEuMultiplierInfo(multiplier.value())
                : null;
            case EU_MODIFIER_NOT_LIMITING_PARALLEL, OVERCLOCK, TIER_SKIPS, HEAT, RECIPE_OVERRIDE, POWER, OUTPUT -> null;
        };
    }

    @Nullable
    private static Consumer<MultiblockTooltipBuilder> parallel(Formula formula) {
        return switch (formula) {
            case Formula.Constant constant -> tt -> tt.addStaticParallelInfo((int) constant.value());
            case Formula.Supplied supplied -> tt -> tt.addStaticParallelInfo(
                (int) supplied.value()
                    .getAsDouble());
            case Formula.TierProduct product -> product.kinds()
                .size() == 1 ? tt -> tt.addDynamicParallelInfo(
                    product.factor(),
                    product.kinds()
                        .get(0))
                    : tt -> tt.addTierProductParallelInfo(product.factor(), product.kinds());
            case Formula.PerVoltageTier perTier -> tt -> tt.addVoltageParallelInfo(perTier.perTier());
            case Formula.RisingPerVoltageTier rising -> tt -> tt
                .addRisingParallelPerVoltageTierInfo(rising.min(), rising.max(), rising.kind());
            case Formula.PerTier perTier -> null;
            case Formula.CompoundPerTier compound -> null;
            case Formula.Rising rising -> null;
            case Formula.AtLeast atLeast -> null;
            case Formula.Custom custom -> null;
        };
    }

    @Nullable
    private static Consumer<MultiblockTooltipBuilder> speed(Formula formula) {
        return switch (formula) {
            case Formula.Constant constant -> tt -> tt.addStaticSpeedInfo((float) constant.value());
            case Formula.PerTier perTier -> switch (perTier.firstTier()) {
                case 0 -> tt -> tt
                    .addSpeedPerTierInfo((float) perTier.base(), (float) perTier.perTier(), perTier.kind());
                case 1 -> tt -> tt
                    .addSpeedPerTierBeyondFirstInfo((float) perTier.base(), (float) perTier.perTier(), perTier.kind());
                default -> null;
            };
            case Formula.Rising rising -> tt -> tt
                .addRisingSpeedInfo((float) rising.min(), (float) rising.max(), rising.kind());
            case Formula.Supplied supplied -> null;
            case Formula.TierProduct product -> null;
            case Formula.PerVoltageTier perTier -> null;
            case Formula.RisingPerVoltageTier rising -> null;
            case Formula.CompoundPerTier compound -> null;
            case Formula.AtLeast atLeast -> null;
            case Formula.Custom custom -> null;
        };
    }

    @Nullable
    private static Consumer<MultiblockTooltipBuilder> euModifier(Formula formula) {
        return switch (formula) {
            case Formula.Constant constant -> tt -> tt.addStaticEuEffInfo((float) constant.value());
            case Formula.PerTier perTier -> perTier.base() == 1 && perTier.firstTier() == 0
                ? tt -> tt.addDynamicEuEffInfo((float) -perTier.perTier(), perTier.kind())
                : null;
            case Formula.CompoundPerTier compound -> tt -> tt.addStaticEuEffInfo((float) compound.base())
                .addEuMultiplierBeyondFirstInfo((float) compound.factor(), compound.kind());
            case Formula.AtLeast atLeast -> {
                Consumer<MultiblockTooltipBuilder> lines = euModifier(atLeast.formula());
                yield lines == null ? null
                    : lines.andThen(tt -> tt.addMaxEuDiscountInfo((float) (1 - atLeast.floor())));
            }
            case Formula.Supplied supplied -> null;
            case Formula.TierProduct product -> null;
            case Formula.PerVoltageTier perTier -> null;
            case Formula.Rising rising -> null;
            case Formula.RisingPerVoltageTier rising -> null;
            case Formula.Custom custom -> null;
        };
    }
}
