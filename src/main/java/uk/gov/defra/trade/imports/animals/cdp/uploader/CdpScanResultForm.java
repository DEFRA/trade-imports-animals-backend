package uk.gov.defra.trade.imports.animals.cdp.uploader;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the {@code form} section of a cdp-uploader scan result callback.
 *
 * <p>Must be a mutable POJO (not a record) so that Jackson can use {@link JsonAnySetter} to
 * populate the dynamic {@code files} map, where each key is the form field name and the value is a
 * {@link CdpScanResultFile}.
 */
@Data
@NoArgsConstructor
public class CdpScanResultForm {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Map<String, CdpScanResultFile> files = new LinkedHashMap<>();

  /**
   * Constructs a {@code CdpScanResultForm} with a defensive copy of the supplied files map so
   * that callers passing an unmodifiable map do not cause {@link
   * java.lang.UnsupportedOperationException} when {@link #addField} is later invoked.
   *
   * @param files the initial file entries; must not be {@code null}
   */
  public CdpScanResultForm(Map<String, CdpScanResultFile> files) {
    this.files = new LinkedHashMap<>(files);
  }

  /**
   * Jackson entry point, called once per key under {@code form}. The browser submits its text
   * fields alongside the file and cdp-uploader echoes every one of them here, so a value that is
   * not a file entry is skipped: a document's details reach this service when its upload is
   * claimed, not from the scan callback.
   *
   * @param fieldName the multipart field name
   * @param value     the field's value — a file entry, or a text value to skip
   */
  @JsonAnySetter
  public void addField(String fieldName, Object value) {
    if (value instanceof Map<?, ?> entry && entry.containsKey("fileId")) {
      files.put(fieldName, MAPPER.convertValue(entry, CdpScanResultFile.class));
    }
  }
}
