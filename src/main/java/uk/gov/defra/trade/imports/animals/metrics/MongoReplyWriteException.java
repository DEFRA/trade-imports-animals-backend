package uk.gov.defra.trade.imports.animals.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.BsonDocument;
import org.bson.BsonValue;

final class MongoReplyWriteException extends RuntimeException {

  private MongoReplyWriteException(String writeFailures) {
    super(writeFailures, null, false, false);
  }

  static Optional<MongoReplyWriteException> reportedBy(BsonDocument reply) {
    List<String> writeFailures = new ArrayList<>();
    BsonValue writeErrors = reply.get("writeErrors");
    if (writeErrors != null && writeErrors.isArray()) {
      List<String> labels =
          writeErrors.asArray().stream()
              .map(BsonValue::asDocument)
              .filter(error -> !MongoCallRecorder.isDuplicateKey(error.getNumber("code").intValue()))
              .map(MongoReplyWriteException::errorCodeLabelOf)
              .toList();
      if (!labels.isEmpty()) {
        writeFailures.add("writeErrors " + labels);
      }
    }
    if (reply.isDocument("writeConcernError")) {
      writeFailures.add(
          "writeConcernError " + errorCodeLabelOf(reply.getDocument("writeConcernError")));
    }
    return writeFailures.isEmpty()
        ? Optional.empty()
        : Optional.of(new MongoReplyWriteException(String.join("; ", writeFailures)));
  }

  private static String errorCodeLabelOf(BsonDocument error) {
    String code = String.valueOf(error.getNumber("code").intValue());
    return error.isString("codeName") ? code + " " + error.getString("codeName").getValue() : code;
  }
}
