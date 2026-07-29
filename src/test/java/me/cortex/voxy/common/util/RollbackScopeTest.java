package me.cortex.voxy.common.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RollbackScopeTest {
    @Test
    void rollsBackActiveActionsInReverseOrder() {
        var calls = new ArrayList<String>();

        try (var scope = new RollbackScope()) {
            scope.defer(() -> calls.add("first"));
            var cancelled = scope.defer(() -> calls.add("cancelled"));
            scope.defer(() -> calls.add("last"));
            cancelled.cancel();
        }

        assertEquals(List.of("last", "first"), calls);
    }

    @Test
    void commitTransfersOwnershipWithoutRunningCleanup() {
        var calls = new ArrayList<String>();

        try (var scope = new RollbackScope()) {
            scope.defer(() -> calls.add("cleanup"));
            scope.commit();
        }

        assertEquals(List.of(), calls);
    }

    @Test
    void aggregatesFailuresAfterAttemptingEveryCleanup() {
        var calls = new ArrayList<String>();
        var first = new IllegalStateException("first");
        var last = new IllegalArgumentException("last");

        var thrown = assertThrows(IllegalArgumentException.class, () -> {
            try (var scope = new RollbackScope()) {
                scope.defer(() -> {
                    calls.add("first");
                    throw first;
                });
                scope.defer(() -> {
                    calls.add("last");
                    throw last;
                });
            }
        });

        assertSame(last, thrown);
        assertEquals(List.of("last", "first"), calls);
        assertEquals(List.of(first), List.of(thrown.getSuppressed()));
    }
}
