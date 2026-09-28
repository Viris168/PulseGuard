package com.viris.PulseGuard.heartbeat;

import com.viris.PulseGuard.monitor.Monitor;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One call to a heartbeat's ping URL. */
@Entity
@Table(name = "pings")
@Getter
@Setter
@NoArgsConstructor
public class Ping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monitor_id", nullable = false)
    private Monitor monitor;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "source_ip", nullable = false, length = 45)
    private String sourceIp;
}
