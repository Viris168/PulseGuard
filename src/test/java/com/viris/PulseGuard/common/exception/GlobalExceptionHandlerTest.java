package com.viris.PulseGuard.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void concurrentEditReturnsConflictWithoutInternalDetails() {
        // What Hibernate actually throws for a stale @Version (see the step 4.6 demo).
        var ex = new ObjectOptimisticLockingFailureException("com.viris.PulseGuard.monitor.Monitor", 42L);

        ResponseEntity<ApiError> response = handler.handleConcurrentUpdate(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message())
                .isEqualTo("This resource was changed by someone else. Reload and try again.")
                .doesNotContain("com.viris");
    }
}
