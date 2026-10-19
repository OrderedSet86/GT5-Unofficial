package gregtech.api.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import gregtech.api.enums.VoltageIndex;
import gregtech.api.util.GTStructureUtility;

class ModifierTest {

    private static final class Machine {

        int pipe = -1;

        int getPipe() {
            return pipe;
        }

        void setPipe(int tier) {
            pipe = tier;
        }
    }

    private static final ModifierKind.IntKind PRESSURE = ModifierKind.ofInt("test:pressure")
        .ordered()
        .range(1, 2)
        .labels(1, "Basic", "High Pressure")
        .register();

    private static final Modifier.Of<Machine, Integer> ITEM_PIPE = Modifier
        .itemPipeCasing(Machine::getPipe, Machine::setPipe);

    @Test
    void aSharedModifierBindsToEachMachine() {
        Machine first = new Machine();
        Machine second = new Machine();

        ITEM_PIPE.of(first)
            .set(3);

        assertEquals(3, first.pipe);
        assertEquals(
            -1,
            ITEM_PIPE.of(second)
                .get());
        assertSame(ModifierKind.ITEM_PIPE_CASING, ITEM_PIPE.of(first).kind);
    }

    @ParameterizedTest
    @MethodSource("pipeCasingKinds")
    void aPipeCasingRangeIsTheCasingList(ModifierKind kind, int casingTiers) {
        assertEquals(new ModifierKind.Range(1, casingTiers), kind.getRange());
    }

    private static Stream<Arguments> pipeCasingKinds() {
        return Stream.of(
            Arguments.of(ModifierKind.PIPE_CASING, GTStructureUtility.PIPE_CASING_TIERS),
            Arguments.of(ModifierKind.ITEM_PIPE_CASING, GTStructureUtility.ITEM_PIPE_CASING_TIERS));
    }

    @Test
    void theKindNamesItsValues() {
        int[] tier = { 1 };
        Modifier modifier = Modifier.of(PRESSURE, () -> tier[0], value -> tier[0] = value);

        modifier.set(2);

        assertEquals(2, tier[0]);
        assertEquals("High Pressure", PRESSURE.label(2));
        assertEquals("Basic", PRESSURE.label(1));
        assertEquals("3", PRESSURE.label(3));
        assertEquals("LuV", ModifierKind.VOLTAGE.label(VoltageIndex.LuV));
    }

    /**
     * No default arm: after a new {@link ModifierKind}, this test compiles only once the switch has a case for the new
     * kind.
     */
    @Test
    void aPlannerHandlesEachKindOfValue() {
        ModifierKind kind = ModifierKind.COIL;
        String control = switch (kind) {
            case ModifierKind.IntKind tier -> "slider";
        };
        assertEquals("slider", control);
    }

    @Test
    void kindsAreRegisteredOnceById() {
        assertTrue(
            ModifierKind.all()
                .contains(PRESSURE));
        assertTrue(
            ModifierKind.all()
                .contains(ModifierKind.COIL));
        assertThrows(
            IllegalStateException.class,
            () -> ModifierKind.ofInt("test:pressure")
                .register());
    }
}
