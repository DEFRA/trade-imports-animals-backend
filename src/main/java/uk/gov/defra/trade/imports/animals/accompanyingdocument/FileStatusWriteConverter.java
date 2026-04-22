package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;

/**
 * MongoDB writing converter: maps a {@link FileStatus} enum constant to the lowercase string that
 * cdp-uploader uses and that will be stored in MongoDB. Registered in {@code MongoConfig}.
 */
@WritingConverter
public class FileStatusWriteConverter implements Converter<FileStatus, String> {

  @Override
  public String convert(FileStatus source) {
    return switch (source) {
      case COMPLETE -> "complete";
      case REJECTED -> "rejected";
    };
  }
}
