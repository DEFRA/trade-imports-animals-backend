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
    if (writeErrors != null && writeErrors.isArray() && !writeErrors.asArray().isEmpty()) {
      List<String> codes =
          writeErrors.asArray().stream()
              .map(BsonValue::asDocument)
              .map(MongoReplyWriteException::errorCodeOf)
              .toList();
      writeFailures.add("writeErrors codes " + codes);
    }
    if (reply.isDocument("writeConcernError")) {
      writeFailures.add(
          "writeConcernError code " + errorCodeOf(reply.getDocument("writeConcernError")));
    }
    return writeFailures.isEmpty()
        ? Optional.empty()
        : Optional.of(new MongoReplyWriteException(String.join("; ", writeFailures)));
  }

  private static String errorCodeOf(BsonDocument error) {
    String code = String.valueOf(error.getNumber("code").intValue());
    return error.isString("codeName") ? code + " " + error.getString("codeName").getValue() : code;
  }
}
