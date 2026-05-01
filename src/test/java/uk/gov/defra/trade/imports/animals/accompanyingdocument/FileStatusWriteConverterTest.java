package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FileStatusWriteConverterTest {

    private final FileStatusWriteConverter converter = new FileStatusWriteConverter();

    @Test
    void convert_shouldReturnComplete_whenStatusIsComplete() {
        assertThat(converter.convert(FileStatus.COMPLETE)).isEqualTo("complete");
    }

    @Test
    void convert_shouldReturnRejected_whenStatusIsRejected() {
        assertThat(converter.convert(FileStatus.REJECTED)).isEqualTo("rejected");
    }
}
