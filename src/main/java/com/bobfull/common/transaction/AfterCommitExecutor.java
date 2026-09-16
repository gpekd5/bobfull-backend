package com.bobfull.common.transaction;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 롤백된 변경의 후속 작업이 실행되지 않도록 작업을 트랜잭션 커밋 이후로 지연한다.
public final class AfterCommitExecutor {

    private AfterCommitExecutor() {
    }

    // 활성 트랜잭션이 없으면 즉시 실행하고, 있으면 afterCommit callback으로 등록한다.
    public static void run(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }
}
