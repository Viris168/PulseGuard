package com.viris.PulseGuard.check.dto;

import com.viris.PulseGuard.check.Check;
import com.viris.PulseGuard.enumeration.CheckResult;
import com.viris.PulseGuard.enumeration.ErrorType;

import java.time.Instant;

/** One row of check history; mirrors {@code Check} in frontend/src/types/check.ts. */
public record CheckResponse(
        Long id,
        Long monitorId,
        CheckResult result,
        Integer statusCode,
        Integer responseTimeMs,
        ErrorType errorType,
        String errorMessage,
        Instant checkedAt
) {
    public static CheckResponse from(Check check) {
        return new CheckResponse(check.getId(), check.getMonitor().getId(), check.getResult(),
                check.getStatusCode(), check.getResponseTimeMs(), check.getErrorType(),
                check.getErrorMessage(), check.getCheckedAt());
    }
}
