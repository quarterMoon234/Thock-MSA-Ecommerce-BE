package com.thock.back.market.config;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

/**
 * 서킷 브레이커는 "의존 서비스 불안정"만 실패로 집계해야 한다.
 * 4xx 같은 비즈니스 오류까지 실패율에 포함하면 정상 서비스에도 서킷이 잘못 열릴 수 있다.
 */
public class ExternalServiceCircuitBreakerRecordFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        Throwable root = unwrap(throwable);

        if (root instanceof CallNotPermittedException) {
            return false;
        }

        if (root instanceof RetryableException) {
            return true;
        }

        if (root instanceof FeignException feignException) {
            return feignException.status() >= 500 || feignException.status() == -1;
        }

        return false;
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;

        while (true) {
            if (current instanceof CompletionException || current instanceof ExecutionException) {
                if (current.getCause() == null) {
                    return current;
                }
                current = current.getCause();
                continue;
            }
            if (current instanceof UndeclaredThrowableException undeclaredThrowableException) {
                if (undeclaredThrowableException.getUndeclaredThrowable() == null) {
                    return current;
                }
                current = undeclaredThrowableException.getUndeclaredThrowable();
                continue;
            }
            return current;
        }
    }
}
