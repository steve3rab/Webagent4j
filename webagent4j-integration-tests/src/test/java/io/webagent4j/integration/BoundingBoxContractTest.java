package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.dom.BoundingBox;
import org.junit.jupiter.api.Test;

class BoundingBoxContractTest {

    @Test
    void acceptsFiniteCoordinatesAndNonNegativeDimensions() {
        BoundingBox box = new BoundingBox(-10.5, 20.0, 0.0, 42.5);

        assertThat(box.x()).isEqualTo(-10.5);
        assertThat(box.width()).isZero();
    }

    @Test
    void rejectsNonFiniteOrNegativeGeometry() {
        assertThatThrownBy(() -> new BoundingBox(Double.NaN, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundingBox(0, 0, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundingBox(0, 0, 1, Double.POSITIVE_INFINITY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
