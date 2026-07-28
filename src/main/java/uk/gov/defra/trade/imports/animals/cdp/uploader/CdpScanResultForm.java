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
 * <p>Per the cdp-uploader API (see the {@code /Callback} section of
 * {@code DEFRA/cdp-uploader/main/README.md}), the {@code form} object holds every field the
 * browser submitted alongside the file — file entries **and** any plain text form values. This
 * class exposes both:
 *
 * <ul>
 *   <li>{@link #getFiles()} — file entries keyed by their multipart field name.</li>
 *   <li>{@link #getTextFields()} — non-file form fields (e.g. {@code documentType},
 *       {@code documentReference}, hidden crumb inputs) keyed by field name, values as strings.</li>
 * </ul>
 *
 * <p>Must be a mutable POJO (not a record) so that Jackson can use {@link JsonAnySetter} to
 * populate the maps dynamically. The setter dispatches on the runtime type Jackson delivers:
 * nested JSON objects with a {@code fileId} key are treated as file entries and converted to
 * {@link CdpScanResultFile}; JSON scalars are treated as text values.
 */
@Data
@NoArgsConstructor
public class CdpScanResultForm {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Map<String, CdpScanResultFile> files = new LinkedHashMap<>();

  private Map<String, String> textFields = new LinkedHashMap<>();

  /**
   * Constructs a {@code CdpScanResultForm} with a defensive copy of the supplied files map so
   * that callers passing an unmodifiable map do not cause {@link
   * java.lang.UnsupportedOperationException} on subsequent mutation via {@link #getFiles()}.
   *
   * @param files the initial file entries; must not be {@code null}
   */
  public CdpScanResultForm(Map<String, CdpScanResultFile> files) {
    this.files = new LinkedHashMap<>(files);
  }

  /**
   * Jackson entry point — invoked reflectively during deserialization of a callback body, once
   * per unknown key encountered under {@code form}. Not intended for direct use by application
   * code; construct a form programmatically via {@link #CdpScanResultForm()} +
   * {@link #getFiles()} / {@link #getTextFields()} instead.
   *
   * <p>Dispatch is on runtime type: a nested JSON object with a {@code fileId} key is deserialized
   * into a {@link CdpScanResultFile} and stored under {@link #getFiles()}; a JSON scalar string is
   * stored under {@link #getTextFields()}. cdp-uploader's contract (per its README) only surfaces
   * these two shapes under {@code form}, so other types (numbers, booleans, arrays, non-file
   * objects) are silently ignored.
   */
  @JsonAnySetter
  public void addField(String fieldName, Object value) {
    if (value instanceof Map<?, ?> nested && nested.containsKey("fileId")) {
      files.put(fieldName, MAPPER.convertValue(nested, CdpScanResultFile.class));
    } else if (value instanceof String text) {
      textFields.put(fieldName, text);
    }
  }
}
