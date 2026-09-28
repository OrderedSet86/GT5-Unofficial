package gregtech.api.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MultiblockTooltipBuilderTest {

    @Test
    void theDeprecatedStructureLineMarksTheStructureDeprecated() {
        assertFalse(
            new MultiblockTooltipBuilder().addMachineType("Test")
                .isStructureDeprecated());
        assertTrue(
            new MultiblockTooltipBuilder().addMachineType("Test")
                .addStructureDeprecatedLine()
                .isStructureDeprecated());
    }
}
