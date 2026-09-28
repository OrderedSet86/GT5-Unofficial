package gregtech.api.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import gregtech.api.enums.VoltageIndex;
import gregtech.api.util.GTStructureUtility;
import gregtech.api.util.tooltip.TooltipTier;

class StructureParameterTest {

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

    private static final StructureParameter.Of<Machine, Byte> SOLENOID = StructureParameter
        .solenoid(Machine::getSolenoid, Machine::setSolenoid);
    private static final StructureParameter.Of<Machine, Integer> ITEM_PIPE = StructureParameter
        .itemPipeCasing(Machine::getPipe, Machine::setPipe);

    @Test
    void aSharedParameterBindsToEachMachine() {
        Machine first = new Machine();
        Machine second = new Machine();

        SOLENOID.of(first)
            .set(VoltageIndex.LuV);

        assertEquals(VoltageIndex.LuV, (byte) first.solenoid);
        assertEquals(
            0,
            SOLENOID.of(second)
                .get());
        assertEquals(
            "LuV",
            SOLENOID.of(first)
                .label(VoltageIndex.LuV));
    }

    @Test
    void theItemPipeRangeIsTheCasingList() {
        StructureParameter pipe = ITEM_PIPE.of(new Machine());

        assertEquals(1, pipe.min);
        assertEquals(GTStructureUtility.ITEM_PIPE_CASING_TIERS, pipe.max);
        assertEquals(-1, pipe.get());
        assertThrows(IllegalArgumentException.class, () -> pipe.set(0));
    }

    @Test
    void derivingAfterSetRunsOnlyForInspection() {
        Machine machine = new Machine();
        StructureParameter.Of<Machine, Integer> deriving = ITEM_PIPE.derivingAfterSet(m -> m.derived = 2 * m.pipe);

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
    void theBuilderNamesItsValues() {
        int[] tier = { 1 };
        StructureParameter parameter = StructureParameter.builder(TooltipTier.STRUCTURE)
            .between(1, 2)
            .labels("Basic", "High Pressure")
            .getter(() -> tier[0])
            .setter(value -> tier[0] = value)
            .build();

        parameter.set(2);

        assertEquals(2, tier[0]);
        assertEquals("High Pressure", parameter.label(2));
        assertEquals("Basic", parameter.label(1));
    }

    @Test
    void anEnumPartIsNumberedByPosition() {
        Blade[] blade = { null };
        StructureParameter parameter = StructureParameter
            .ofEnum(TooltipTier.SAWBLADE, Blade.values(), () -> blade[0], value -> blade[0] = value);

        assertEquals(-1, parameter.get());
        parameter.set(1);

        assertEquals(Blade.DIAMOND, blade[0]);
        assertEquals(1, parameter.max);
        assertEquals("DIAMOND", parameter.label(1));
    }
}
