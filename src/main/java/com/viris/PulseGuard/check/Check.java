package com.viris.PulseGuard.check;

import com.viris.PulseGuard.monitor.Monitor;
import com.viris.PulseGuard.enumeration.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "checks")
@Getter
@Setter
@NoArgsConstructor
public class Check {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monitor_id", nullable = false)
    private Monitor monitor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CheckResult result;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "response_time_ms")
    private Integer responseTimeMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_type", length = 30)
    private ErrorType errorType;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt = Instant.now();
}
