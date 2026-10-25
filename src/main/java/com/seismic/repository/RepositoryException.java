package com.seismic.repository;

/**
 * A persistence operation failed. Unchecked so that callers in the service layer decide where to handle
 * it (show a message, raise a PROCESSING_ERROR alert) instead of every method declaring SQLException.
 */
public class RepositoryException extends RuntimeException {

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
