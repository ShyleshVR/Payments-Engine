package com.shylesh.notification_service.config;

import org.hibernate.exception.JDBCConnectionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.net.ConnectException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.List;

/**
 * Decides whether a consumer failure is caused by infrastructure that will come back (the
 * database being down, a connection pool timeout), as opposed to a problem with the message.
 * Infrastructure failures are retried for as long as they last: dead-lettering a valid event
 * because the database blinked would silently lose it (nothing reads the consumer DLT).
 */
public final class ConsumerFailureClassifier {

    private static final List<Class<? extends Throwable>> TRANSIENT = List.of(
            TransientDataAccessException.class,
            RecoverableDataAccessException.class,
            DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class,
            JDBCConnectionException.class,
            SQLTransientException.class,
            SQLRecoverableException.class,
            ConnectException.class
    );

    private ConsumerFailureClassifier() {
    }

    public static boolean isTransientInfrastructureFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            for (Class<? extends Throwable> type : TRANSIENT) {
                if (type.isInstance(t)) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
