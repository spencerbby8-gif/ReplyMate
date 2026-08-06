package com.replymate.core.util;

/** Lightweight success/failure carrier used across layers instead of throwing. */
public final class Result<T> {
    public final boolean ok;
    public final T value;
    public final String error;      // human-readable, safe to show in UI

    private Result(boolean ok, T value, String error) {
        this.ok = ok;
        this.value = value;
        this.error = error;
    }

    public static <T> Result<T> ok(T value) {
        return new Result<T>(true, value, null);
    }

    public static <T> Result<T> err(String error) {
        return new Result<T>(false, null, error == null ? "unknown error" : error);
    }

    /** Map the value if ok, keep the error otherwise. */
    public <R> Result<R> map(Mapper<T, R> fn) {
        if (!ok) return Result.err(error);
        try {
            return Result.ok(fn.apply(value));
        } catch (RuntimeException e) {
            return Result.err(e.getMessage());
        }
    }

    public interface Mapper<A, B> { B apply(A a); }
}
