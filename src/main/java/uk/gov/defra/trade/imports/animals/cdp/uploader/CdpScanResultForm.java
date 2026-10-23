package uk.gov.defra.trade.imports.animals.cdp.uploader;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Represents the {@code form} section of a cdp-uploader scan result callback, holding the scanned
 * file entries keyed by multipart field name.
 */
@Data
@NoArgsConstructor
@JsonDeserialize(using = CdpScanResultForm.Deserializer.class)
public class CdpScanResultForm {

  private Map<String, CdpScanResultFile> files = new LinkedHashMap<>();

  /**
   * Creates a form holding a mutable copy of the supplied file entries.
   *
   * @param files the initial file entries, keyed by multipart field name; must not be {@code null}
   */
  public CdpScanResultForm(Map<String, CdpScanResultFile> files) {
    this.files = new LinkedHashMap<>(files);
  }

  /**
   * Records a scanned file entry under its multipart field name.
   *
   * @param fieldName the multipart field name
   * @param file      the scanned file entry
   */
  public void addFileEntry(String fieldName, CdpScanResultFile file) {
    files.put(fieldName, file);
  }

  @Slf4j
  static class Deserializer extends JsonDeserializer<CdpScanResultForm> {

    private static final String FILE_ENTRY_MARKER = "fileId";

    @Override
    public CdpScanResultForm deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode root = parser.readValueAsTree();
      CdpScanResultForm form = new CdpScanResultForm();
      for (Map.Entry<String, JsonNode> formValue : root.properties()) {
        String fieldName = formValue.getKey();
        JsonNode value = formValue.getValue();
        if (isFileEntry(value)) {
          form.addFileEntry(fieldName, context.readTreeAsValue(value, CdpScanResultFile.class));
        } else if (value.isObject()) {
          log.warn(
              "Scan result form field {} is an object with no fileId, so no file entry was"
                  + " recorded for it",
              fieldName);
        }
      }
      return form;
    }

    private static boolean isFileEntry(JsonNode value) {
      return value.isObject() && value.has(FILE_ENTRY_MARKER);
    }
  }
}
