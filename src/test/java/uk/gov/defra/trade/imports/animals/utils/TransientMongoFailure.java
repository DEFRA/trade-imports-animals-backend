package uk.gov.defra.trade.imports.animals.utils;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
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
        return commandFailure(112, "WriteConflict",
            "Caused by :: Collection namespace 'trade-imports-animals-backend.outbox' is already "
                + "in use.",
            "TransientTransactionError");
    }

    /**
     * The other label a commit can come back with, and the one that must <em>not</em> be retried
     * from the top. {@code UnknownTransactionCommitResult} says the commit may already have
     * succeeded; MongoDB's contract is to retry {@code commitTransaction} on the same session, and
     * re-running the method body would repeat work against state the first attempt has already
     * changed.
     */
    public static TransactionSystemException unknownCommitResultAtCommit() {
        return new TransactionSystemException("Could not commit Mongo transaction",
            commandFailure(91, "ShutdownInProgress", "The server is shutting down",
                "UnknownTransactionCommitResult"));
    }

    /**
     * A transient failure that is <em>not</em> a {@link MongoCommandException}. The driver labels
     * a commit lost to a network blip {@code TransientTransactionError} too, and it arrives as a
     * plain {@link MongoException} with no server response to quote — so the interceptor's log
     * summary has to fall back to the exception's own type and message.
     */
    public static TransactionSystemException transientNetworkFailureAtCommit() {
        MongoException cause = new MongoException("Connection reset by peer");
        cause.addLabel("TransientTransactionError");
        return new TransactionSystemException("Could not commit Mongo transaction", cause);
    }

    private static MongoCommandException commandFailure(int code, String codeName, String errmsg,
        String errorLabel) {
        BsonDocument response = new BsonDocument()
            .append("ok", new BsonDouble(0.0))
            .append("code", new BsonInt32(code))
            .append("codeName", new BsonString(codeName))
            .append("errmsg", new BsonString(errmsg))
            .append("errorLabels", new BsonArray(List.of(new BsonString(errorLabel))));
        return new MongoCommandException(response, new ServerAddress());
    }
}
