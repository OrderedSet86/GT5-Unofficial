package gregtech.api.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import gregtech.api.enums.VoltageIndex;
import gregtech.api.util.GTStructureUtility;

class ModifierTest {

    private static final class Machine {

        Byte solenoid;
        int pipe = -1;

        Byte getSolenoid() {
            return solenoid;
        }

        void setSolenoid(byte tier) {
            solenoid = tier;
        }

        int getPipe() {
            return pipe;
        }

        void setPipe(int tier) {
            pipe = tier;
        }
    }

    private enum Blade {
        STEEL,
        DIAMOND
    }

    private static final ModifierKind.IntKind PRESSURE = ModifierKind.ofInt("test:pressure")
        .ordered()
        .range(1, 2)
        .labels(1, "Basic", "High Pressure")
        .register();
    private static final ModifierKind.IntKind BLADE = ModifierKind.ofEnum("test:blade", Blade.values())
        .source(ModifierKind.Source.ITEM)
        .register();

    private static final Modifier.Of<Machine, Byte> SOLENOID = Modifier
        .solenoid(Machine::getSolenoid, Machine::setSolenoid);
    private static final Modifier.Of<Machine, Integer> ITEM_PIPE = Modifier
        .itemPipeCasing(Machine::getPipe, Machine::setPipe);

    @Test
    void aSharedModifierBindsToEachMachine() {
        Machine first = new Machine();
        Machine second = new Machine();

        SOLENOID.of(first)
            .set(VoltageIndex.LuV);

        assertEquals(VoltageIndex.LuV, (byte) first.solenoid);
        assertEquals(
            0,
            SOLENOID.of(second)
                .get());
        assertSame(ModifierKind.SOLENOID, SOLENOID.of(first).kind);
        assertEquals("LuV", ModifierKind.SOLENOID.label(VoltageIndex.LuV));
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
    }

    @Test
    void anEnumPartIsNumberedByOrdinal() {
        Blade[] blade = { null };
        Modifier modifier = Modifier.ofEnum(BLADE, Blade.values(), () -> blade[0], value -> blade[0] = value);

        assertEquals(-1, modifier.get());
        modifier.set(1);

        assertEquals(Blade.DIAMOND, blade[0]);
        assertEquals(
            1,
            BLADE.getRange()
                .max());
        assertEquals("DIAMOND", BLADE.label(1));
        assertEquals("None", BLADE.label(-1));
        assertFalse(BLADE.ordered);
        assertEquals(ModifierKind.Source.ITEM, BLADE.source);
    }

    @Test
    void aValueHasItsKindsType() {
        ModifierKind.LongKind stored = ModifierKind.ofLong("test:stored_fluid")
            .source(ModifierKind.Source.RUNTIME)
            .register();
        long[] litres = { 0 };
        Modifier modifier = Modifier.of(stored, () -> litres[0], value -> litres[0] = value);

        modifier.set(10_000_000_000L);
        ProcessingInputs inputs = ProcessingInputs.builder()
            .modifiers(List.of(modifier))
            .value(ModifierKind.COIL, 3)
            .build();
        long amount = inputs.value(stored);
        int coil = inputs.value(ModifierKind.COIL);

        assertEquals(10_000_000_000L, amount);
        assertEquals(3, coil);
    }

    /** No default arm: a new kind of value stops this compiling until it is handled. */
    @Test
    void aPlannerHandlesEachKindOfValue() {
        for (ModifierKind kind : List.of(ModifierKind.COIL, ModifierKind.ofLong("test:litres")
            .register())) {
            String control = switch (kind) {
                case ModifierKind.IntKind tier -> "slider";
                case ModifierKind.LongKind amount -> "number field";
            };
            assertEquals(kind == ModifierKind.COIL ? "slider" : "number field", control);
        }
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
