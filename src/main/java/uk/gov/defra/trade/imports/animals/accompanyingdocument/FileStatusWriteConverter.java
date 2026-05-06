package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

/**
 * MongoDB writing converter: maps a {@link FileStatus} enum constant to its storage value.
 * Registered in {@code MongoConfig}. The mapping itself lives on {@link FileStatus}.
 */
@WritingConverter
public class FileStatusWriteConverter implements Converter<FileStatus, String> {

  @Override
  public String convert(FileStatus source) {
    return source.storageValue();
  }
}
