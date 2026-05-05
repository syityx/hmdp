package com.syit.hmdp.ws;

public class UserContext {

    private static final ThreadLocal<Long> TL = new ThreadLocal<>();

    public static void set(Long userId) {
        TL.set(userId);
    }

    public static Long get() {
        return TL.get();
    }

    public static void clear() {
        TL.remove();
    }
}
