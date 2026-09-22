package com.viris.PulseGuard.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, Long> {

    List<NotificationChannel> findAllByUserId(Long userId);

    Optional<NotificationChannel> findByIdAndUserId(Long id, Long userId);

    List<NotificationChannel> findAllByUserIdAndEnabledTrue(Long userId);

    long countByUserId(Long userId);
}
