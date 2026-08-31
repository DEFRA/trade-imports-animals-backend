package uk.gov.defra.trade.imports.animals.utils;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import java.util.List;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.springframework.transaction.TransactionSystemException;

/**
 * Builds the failure EUDPA-356 was raised for, in the shape it really arrives in: MongoDB rejects
 * the commit with error 112 {@code WriteConflict} and labels it {@code TransientTransactionError},
 * and Spring's transaction manager wraps that in a {@link TransactionSystemException}.
 *
 * <p>The wrapper matters. {@code TransactionSystemException} sits outside Spring's data-access
 * exception hierarchy, and error 112 translates to the non-transient
 * {@code DataIntegrityViolationException} on the operation path, so nothing but the driver's own
 * error label identifies this as retryable.
 */
public final class TransientMongoFailure {

    private TransientMongoFailure() {
    }

    public static TransactionSystemException writeConflictAtCommit() {
        return new TransactionSystemException("Could not commit Mongo transaction",
            writeConflictOnOutboxCreation());
    }

    public static MongoCommandException writeConflictOnOutboxCreation() {
        BsonDocument response = new BsonDocument()
            .append("ok", new BsonDouble(0.0))
            .append("code", new BsonInt32(112))
            .append("codeName", new BsonString("WriteConflict"))
            .append("errmsg", new BsonString("Caused by :: Collection namespace "
                + "'trade-imports-animals-backend.outbox' is already in use."))
            .append("errorLabels",
                new BsonArray(List.of(new BsonString("TransientTransactionError"))));
        return new MongoCommandException(response, new ServerAddress());
    }
}
