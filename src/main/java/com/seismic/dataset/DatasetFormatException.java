package com.seismic.dataset;

/** The file as a whole cannot be used, for example because required columns are missing. */
public class DatasetFormatException extends Exception {

    public DatasetFormatException(String message) {
        super(message);
    }
}
