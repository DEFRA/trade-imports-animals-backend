package uk.gov.defra.trade.imports.animals.accompanyingdocument;

import com.fasterxml.jackson.annotation.JsonAnySetter;
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

  private Map<String, CdpScanResultFile> files = new LinkedHashMap<>();

  /**
   * Constructs a {@code CdpScanResultForm} with a defensive copy of the supplied files map so
   * that callers passing an unmodifiable map do not cause {@link
   * java.lang.UnsupportedOperationException} when {@link #addFile} is later invoked.
   *
   * @param files the initial file entries; must not be {@code null}
   */
  public CdpScanResultForm(Map<String, CdpScanResultFile> files) {
    this.files = new LinkedHashMap<>(files);
  }

  @JsonAnySetter
  public void addFile(String fieldName, CdpScanResultFile file) {
    files.put(fieldName, file);
  }
}
