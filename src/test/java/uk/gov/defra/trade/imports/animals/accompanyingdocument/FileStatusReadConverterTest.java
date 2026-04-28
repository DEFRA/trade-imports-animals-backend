package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FileStatusReadConverterTest {

    private final FileStatusReadConverter converter = new FileStatusReadConverter();

    @Test
    void convert_shouldReturnComplete_whenSourceIsComplete() {
        assertThat(converter.convert("complete")).isEqualTo(FileStatus.COMPLETE);
    }

    @Test
    void convert_shouldReturnRejected_whenSourceIsRejected() {
        assertThat(converter.convert("rejected")).isEqualTo(FileStatus.REJECTED);
    }

    @Test
    void convert_shouldThrowIllegalArgumentException_whenValueIsUnrecognised() {
        assertThatThrownBy(() -> converter.convert("unknown"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unrecognised FileStatus in MongoDB: unknown");
    }
}
