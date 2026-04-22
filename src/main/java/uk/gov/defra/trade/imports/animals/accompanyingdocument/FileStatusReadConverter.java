package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

/**
 * MongoDB reading converter: maps the lowercase string stored in MongoDB back to the
 * {@link FileStatus} enum constant. Registered in {@code MongoConfig}.
 */
@ReadingConverter
public class FileStatusReadConverter implements Converter<String, FileStatus> {

  @Override
  public FileStatus convert(String source) {
    return switch (source) {
      case "complete" -> FileStatus.COMPLETE;
      case "rejected" -> FileStatus.REJECTED;
      default -> throw new IllegalArgumentException("Unknown FileStatus value: " + source);
    };
  }
}
