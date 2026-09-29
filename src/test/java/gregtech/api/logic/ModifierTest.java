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
        int derived;

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

    private static final ModifierKind<Integer> PRESSURE = ModifierKind.ofInt("test:pressure")
        .ordered()
        .labels(1, "Basic", "High Pressure")
        .register();
    private static final ModifierKind<Integer> BLADE = ModifierKind.ofEnum("test:blade", Blade.values())
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
        Modifier pipe = ITEM_PIPE.of(new Machine());

        assertEquals(1, pipe.min);
        assertEquals(GTStructureUtility.ITEM_PIPE_CASING_TIERS, pipe.max);
        assertEquals(-1, pipe.get());
        assertThrows(IllegalArgumentException.class, () -> pipe.set(0));
    }

    @Test
    void derivingAfterSetRunsOnlyForInspection() {
        Machine machine = new Machine();
        Modifier.Of<Machine, Integer> deriving = ITEM_PIPE.derivingAfterSet(m -> m.derived = 2 * m.pipe);

        ITEM_PIPE.of(machine)
            .set(3);
        assertEquals(0, machine.derived);
        // the structure element sets the value alone; the machine derives after its check
        assertSame(ITEM_PIPE.proxiedElement, deriving.proxiedElement);

        deriving.of(machine)
            .set(4);
        assertEquals(8, machine.derived);
    }

    @Test
    void theKindNamesItsValues() {
        int[] tier = { 1 };
        Modifier<Integer> modifier = Modifier.builder(PRESSURE)
            .between(1, 2)
            .getter(() -> tier[0])
            .setter(value -> tier[0] = value)
            .build();

        modifier.set(2);

        assertEquals(2, tier[0]);
        assertEquals("High Pressure", PRESSURE.label(2));
        assertEquals("Basic", PRESSURE.label(1));
        assertEquals("3", PRESSURE.label(3));
    }

    @Test
    void anEnumPartIsNumberedByOrdinal() {
        Blade[] blade = { null };
        Modifier<Integer> modifier = Modifier.ofEnum(BLADE, Blade.values(), () -> blade[0], value -> blade[0] = value);

        assertEquals(-1, modifier.get());
        modifier.set(1);

        assertEquals(Blade.DIAMOND, blade[0]);
        assertEquals(1, modifier.max);
        assertEquals("DIAMOND", BLADE.label(1));
        assertEquals("None", BLADE.label(-1));
        assertFalse(BLADE.ordered);
        assertEquals(ModifierKind.Source.ITEM, BLADE.source);
    }

    @Test
    void aValueHasItsKindsType() {
        ModifierKind<Long> stored = ModifierKind.ofLong("test:stored_fluid")
            .source(ModifierKind.Source.RUNTIME)
            .register();
        long[] litres = { 0 };
        Modifier<Long> modifier = Modifier.builder(stored)
            .between(0L, 10_000_000_000L)
            .getter(() -> litres[0])
            .setter(value -> litres[0] = value)
            .build();

        modifier.setToMax();
        ProcessingSpec.Inputs inputs = ProcessingSpec.Inputs.builder()
            .modifiers(List.of(modifier))
            .value(ModifierKind.COIL, 3)
            .build();
        long amount = inputs.value(stored);
        int coil = inputs.value(ModifierKind.COIL);

        assertEquals(10_000_000_000L, amount);
        assertEquals(3, coil);
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
