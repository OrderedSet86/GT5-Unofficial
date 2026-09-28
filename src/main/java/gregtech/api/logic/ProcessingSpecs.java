package gregtech.api.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.structure.StructureParameter;

/** The registered multiblocks that declare a {@link ProcessingSpec}, for external tools such as factory planners. */
public final class ProcessingSpecs {

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
     * Checks that every spec's tooltip covers what the spec sets, and that every machine that changes the overclock
     * calculator in code of its own says so with {@link ProcessingSpec.Builder#alsoCustom}.
     *
     * @throws IllegalStateException listing each machine that fails
     */
    public static void check() {
        List<String> problems = new ArrayList<>();
        all().forEach(entry -> {
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
            if (entry.machine()
                .hasCustomOverclockCalculatorForInspection()
                && !entry.spec()
                    .getAlsoCustom()
                    .contains(ProcessingSpec.Quantity.OVERCLOCK)) {
                problems.add(
                    name + " changes the overclock calculator in its processing logic; mark its spec alsoCustom(OVERCLOCK)");
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Processing spec problems:\n  " + String.join("\n  ", problems));
        }
    }
}
