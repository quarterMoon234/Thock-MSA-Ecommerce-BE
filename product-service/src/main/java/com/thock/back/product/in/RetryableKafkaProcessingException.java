package com.thock.back.product.in;

public class RetryableKafkaProcessingException extends RuntimeException {

    public RetryableKafkaProcessingException(String message) {
        super(message);
    }

    public RetryableKafkaProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
