package com.syit.hmdp.utils;

public interface ILock {

    boolean tryLock(long timeoutSec);

    void unlock();
}
