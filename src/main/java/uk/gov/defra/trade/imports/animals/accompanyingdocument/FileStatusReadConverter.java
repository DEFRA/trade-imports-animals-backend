package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

/**
 * MongoDB reading converter: maps a stored string back to its {@link FileStatus} enum constant.
 * Registered in {@code MongoConfig}. The mapping itself lives on {@link FileStatus}.
 */
@ReadingConverter
public class FileStatusReadConverter implements Converter<String, FileStatus> {

  @Override
  public FileStatus convert(String source) {
    return FileStatus.fromStorageValue(source);
  }
}
