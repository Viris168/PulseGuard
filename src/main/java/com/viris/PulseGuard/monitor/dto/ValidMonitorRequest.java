package com.viris.PulseGuard.monitor.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The type-dependent rules of a {@link MonitorRequest}; see {@link MonitorRequestValidator}. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MonitorRequestValidator.class)
public @interface ValidMonitorRequest {

    String message() default "Invalid monitor";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
