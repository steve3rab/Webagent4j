package io.webagent4j.action;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Locks the exact {@code 1.0.0} name-to-ordinal mapping for every {@link ActionStage} constant that
 * existed in that release, so a future addition can never again silently shift an existing
 * constant's ordinal - the regression this test exists to prevent (a {@code 1.1.0} change once
 * inserted two new constants in the middle of this enum, breaking Revapi's {@code
 * java.field.enumConstantOrderChanged} check against the {@code 1.0.0} baseline for eight existing
 * constants). Any constant added after {@code 1.0.0} must be appended at the end and is
 * deliberately not asserted against a fixed ordinal here, since its own ordinal is free to grow as
 * later constants are appended after it.
 */
class ActionStageOrdinalTest {

    @Test
    void preserves10ExactOrdinalMapping() {
        assertThat(ActionStage.ACTION_STARTED.ordinal()).isZero();
        assertThat(ActionStage.TARGET_RESOLUTION_STARTED.ordinal()).isEqualTo(1);
        assertThat(ActionStage.TARGET_RESOLVED.ordinal()).isEqualTo(2);
        assertThat(ActionStage.PRECONDITION_STARTED.ordinal()).isEqualTo(3);
        assertThat(ActionStage.PRECONDITION_COMPLETED.ordinal()).isEqualTo(4);
        assertThat(ActionStage.BACKEND_ACTION_STARTED.ordinal()).isEqualTo(5);
        assertThat(ActionStage.BACKEND_ACTION_COMPLETED.ordinal()).isEqualTo(6);
        assertThat(ActionStage.STABILIZATION_STARTED.ordinal()).isEqualTo(7);
        assertThat(ActionStage.STABILIZATION_COMPLETED.ordinal()).isEqualTo(8);
        assertThat(ActionStage.VERIFICATION_STARTED.ordinal()).isEqualTo(9);
        assertThat(ActionStage.VERIFICATION_COMPLETED.ordinal()).isEqualTo(10);
        assertThat(ActionStage.ACTION_COMPLETED.ordinal()).isEqualTo(11);
        assertThat(ActionStage.ACTION_FAILED.ordinal()).isEqualTo(12);
    }

    @Test
    void preserves10ExactNameMapping() {
        assertThat(ActionStage.values()[0]).isEqualTo(ActionStage.ACTION_STARTED);
        assertThat(ActionStage.values()[1]).isEqualTo(ActionStage.TARGET_RESOLUTION_STARTED);
        assertThat(ActionStage.values()[2]).isEqualTo(ActionStage.TARGET_RESOLVED);
        assertThat(ActionStage.values()[3]).isEqualTo(ActionStage.PRECONDITION_STARTED);
        assertThat(ActionStage.values()[4]).isEqualTo(ActionStage.PRECONDITION_COMPLETED);
        assertThat(ActionStage.values()[5]).isEqualTo(ActionStage.BACKEND_ACTION_STARTED);
        assertThat(ActionStage.values()[6]).isEqualTo(ActionStage.BACKEND_ACTION_COMPLETED);
        assertThat(ActionStage.values()[7]).isEqualTo(ActionStage.STABILIZATION_STARTED);
        assertThat(ActionStage.values()[8]).isEqualTo(ActionStage.STABILIZATION_COMPLETED);
        assertThat(ActionStage.values()[9]).isEqualTo(ActionStage.VERIFICATION_STARTED);
        assertThat(ActionStage.values()[10]).isEqualTo(ActionStage.VERIFICATION_COMPLETED);
        assertThat(ActionStage.values()[11]).isEqualTo(ActionStage.ACTION_COMPLETED);
        assertThat(ActionStage.values()[12]).isEqualTo(ActionStage.ACTION_FAILED);
    }

    @Test
    void newConstantsAreAppendedAfterEvery10Constant() {
        // Both 1.1.0 additions must sort after every 1.0.0 constant's ordinal (12), never between
        // them - the exact shape of the regression this test suite locks down.
        assertThat(ActionStage.POLICY_EVALUATION_STARTED.ordinal()).isGreaterThan(12);
        assertThat(ActionStage.POLICY_EVALUATION_COMPLETED.ordinal()).isGreaterThan(12);
    }
}
