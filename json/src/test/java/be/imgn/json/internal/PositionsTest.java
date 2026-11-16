package be.imgn.json.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.json.JsonPosition;
import org.junit.jupiter.api.Test;

class PositionsTest {

    @Test
    void countsLinesAndColumns() {
        var input = "ab\ncd\r\nef".getBytes(UTF_8);

        assertThat(Positions.of(input, 0)).isEqualTo(new JsonPosition(1, 1));
        assertThat(Positions.of(input, 2)).isEqualTo(new JsonPosition(1, 3));
        assertThat(Positions.of(input, 3)).isEqualTo(new JsonPosition(2, 1));
        assertThat(Positions.of(input, 5)).isEqualTo(new JsonPosition(2, 3));
        assertThat(Positions.of(input, 7)).isEqualTo(new JsonPosition(3, 1));
        assertThat(Positions.of(input, input.length)).isEqualTo(new JsonPosition(3, 3));
    }

    @Test
    void countsColumnsInUtf16CodeUnits() {
        var input = "é€😀x".getBytes(UTF_8);

        assertThat(Positions.of(input, input.length - 1)).isEqualTo(new JsonPosition(1, 1 + "é€😀".length()));
    }
}
