package gregtech.api.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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

    @Test
    void thePipeCasingRangeIsTheCasingList() {
        assertEquals(
            new ModifierRange(ModifierKind.PIPE_CASING, 1, GTStructureUtility.PIPE_CASING_TIERS),
            ModifierKind.PIPE_CASING.getRange());
    }

    @Test
    void theItemPipeRangeIsTheCasingList() {
        ModifierRange range = ModifierKind.ITEM_PIPE_CASING.getRange();

        assertEquals(1, range.min());
        assertEquals(GTStructureUtility.ITEM_PIPE_CASING_TIERS, range.max());
        assertEquals(
            -1,
            ITEM_PIPE.of(new Machine())
                .get());
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

    /** No default arm: a new kind of value stops this compiling until it is handled. */
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
        assertSame(PRESSURE, ModifierKind.byId("test:pressure"));
        assertTrue(
            ModifierKind.all()
                .contains(ModifierKind.COIL));
        assertThrows(
            IllegalStateException.class,
            () -> ModifierKind.ofInt("test:pressure")
                .register());
    }
}
