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

/** Commit failures in the shape MongoDB and Spring's transaction manager really produce them. */
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

    public static TransactionSystemException unknownCommitResultAtCommit() {
        return new TransactionSystemException("Could not commit Mongo transaction",
            unknownCommitResultOnServerShutdown());
    }

    public static MongoCommandException unknownCommitResultOnServerShutdown() {
        return commandFailure(91, "ShutdownInProgress", "The server is shutting down",
            "UnknownTransactionCommitResult");
    }

    /** A transient failure that is not a {@link MongoCommandException}. */
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
