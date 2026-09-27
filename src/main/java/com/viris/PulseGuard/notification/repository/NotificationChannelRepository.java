package com.viris.PulseGuard.notification.repository;

import com.viris.PulseGuard.enumeration.ChannelType;
import com.viris.PulseGuard.notification.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, Long> {

    List<NotificationChannel> findAllByUserId(Long userId);

    List<NotificationChannel> findAllByUserIdOrderByIdAsc(Long userId);

    boolean existsByUserIdAndTypeAndTarget(Long userId, ChannelType type, String target);

    Optional<NotificationChannel> findByIdAndUserId(Long id, Long userId);

    List<NotificationChannel> findAllByUserIdAndEnabledTrue(Long userId);

    long countByUserId(Long userId);
}
