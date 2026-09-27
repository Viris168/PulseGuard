package com.viris.PulseGuard.common.exception;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import tools.jackson.databind.exc.MismatchedInputException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EmailAlreadyUsedException.class)
    public ResponseEntity<ApiError> handleEmailAlreadyUsed(EmailAlreadyUsedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(), ex.getMessage()));
    }

    // Deliberately generic: never reveal whether the email exists.
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiError.of(HttpStatus.UNAUTHORIZED.value(), ex.getMessage()));
    }

    @ExceptionHandler(PasswordUnchangedException.class)
    public ResponseEntity<ApiError> handlePasswordUnchanged(PasswordUnchangedException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<ApiError> handleTooManyAttempts(TooManyAttemptsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiError.of(HttpStatus.TOO_MANY_REQUESTS.value(), ex.getMessage()));
    }

    @ExceptionHandler(MonitorNotFoundException.class)
    public ResponseEntity<ApiError> handleMonitorNotFound(MonitorNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    @ExceptionHandler(IncidentNotFoundException.class)
    public ResponseEntity<ApiError> handleIncidentNotFound(IncidentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    @ExceptionHandler(ChannelNotFoundException.class)
    public ResponseEntity<ApiError> handleChannelNotFound(ChannelNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    /** Same shape as body validation, so the form can show it under the target field. */
    @ExceptionHandler(InvalidChannelTargetException.class)
    public ResponseEntity<ApiError> handleInvalidChannelTarget(InvalidChannelTargetException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Validation failed",
                        Map.of("target", ex.getMessage())));
    }

    @ExceptionHandler(ChannelRuleException.class)
    public ResponseEntity<ApiError> handleChannelRule(ChannelRuleException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getStatus().value(), ex.getMessage()));
    }

    @ExceptionHandler(StatusPageNotFoundException.class)
    public ResponseEntity<ApiError> handleStatusPageNotFound(StatusPageNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(HttpStatus.NOT_FOUND.value(), ex.getMessage()));
    }

    @ExceptionHandler(InvalidStatusPageException.class)
    public ResponseEntity<ApiError> handleInvalidStatusPage(InvalidStatusPageException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), ex.getMessage(), ex.getFieldErrors()));
    }

    @ExceptionHandler(SlugTakenException.class)
    public ResponseEntity<ApiError> handleSlugTaken(SlugTakenException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(), ex.getMessage(),
                        Map.of("slug", "That address is already taken")));
    }

    @ExceptionHandler(InvalidMonitorUrlException.class)
    public ResponseEntity<ApiError> handleInvalidMonitorUrl(InvalidMonitorUrlException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), ex.getMessage()));
    }

    @ExceptionHandler(PlanLimitExceededException.class)
    public ResponseEntity<ApiError> handlePlanLimitExceeded(PlanLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(HttpStatus.FORBIDDEN.value(), ex.getMessage()));
    }

    @ExceptionHandler(BillingRuleException.class)
    public ResponseEntity<ApiError> handleBillingRule(BillingRuleException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getStatus().value(), ex.getMessage()));
    }

    /** Stripe failed or is unreachable; the details are logged where the call was made. */
    @ExceptionHandler(PaymentProviderException.class)
    public ResponseEntity<ApiError> handlePaymentProvider(PaymentProviderException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ApiError.of(HttpStatus.BAD_GATEWAY.value(), ex.getMessage()));
    }

    /** Deliberately vague: an attacker probing the webhook learns nothing about why it failed. */
    @ExceptionHandler(InvalidWebhookException.class)
    public ResponseEntity<ApiError> handleInvalidWebhook(InvalidWebhookException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Invalid webhook signature."));
    }

    /**
     * Only Stripe calls this path. A 500 makes it retry the event later, by which time the
     * missing config or data may be fixed; the cause is logged by StripeWebhookService.
     */
    @ExceptionHandler(BillingSyncException.class)
    public ResponseEntity<ApiError> handleBillingSync(BillingSyncException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Event could not be processed."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Validation failed", fieldErrors));
    }

    /**
     * A body that is not valid JSON, or a value of the wrong kind, e.g. {@code {"plan": "GOLD"}}.
     * Unhandled, Spring forwards it to {@code /error}, which security answers with a 401 — and
     * the frontend treats a 401 as "signed out". For enums the allowed values are listed, as for
     * query parameters; the rejected value is never echoed.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            String field = mismatch.getPath().stream()
                    .map(ref -> ref.getPropertyName() != null ? ref.getPropertyName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining("."));
            Class<?> type = mismatch.getTargetType();
            String reason = type != null && type.isEnum()
                    ? "must be one of " + Arrays.stream(type.getEnumConstants()).map(Object::toString)
                            .collect(Collectors.joining(", "))
                    : "has an invalid format";
            return ResponseEntity.badRequest()
                    .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Validation failed", Map.of(field, reason)));
        }
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Request body is not valid JSON."));
    }

    /**
     * A parameter that cannot be converted at all, e.g. {@code ?status=BOGUS} or
     * {@code ?limit=abc}. The rejected value is not echoed back; for enums the allowed values
     * are listed instead, which is what a client needs to fix the request.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Class<?> type = ex.getRequiredType();
        String reason = type != null && type.isEnum()
                ? "must be one of " + Arrays.stream(type.getEnumConstants()).map(Object::toString)
                        .collect(Collectors.joining(", "))
                : "has an invalid format";
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Validation failed",
                        Map.of(ex.getName(), reason)));
    }

    /**
     * Constraints on {@code @RequestParam}/{@code @PathVariable} (e.g. {@code @Max(100) limit}).
     * Spring's default answer is a 400 with an empty body; this keeps the same JSON shape as
     * body validation, keyed by parameter name.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleParameterValidation(HandlerMethodValidationException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().stream().findFirst()
                    .ifPresent(error -> fieldErrors.putIfAbsent(name, error.getDefaultMessage()));
        }
        return ResponseEntity.badRequest()
                .body(ApiError.of(HttpStatus.BAD_REQUEST.value(), "Validation failed", fieldErrors));
    }

    /**
     * The row changed between this request's read and its write (@Version). Not retried for
     * the user: they edited stale data and should see the current version first. Hibernate's
     * message names internal classes, so it is not passed through.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConcurrentUpdate(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT.value(),
                        "This resource was changed by someone else. Reload and try again."));
    }
}
