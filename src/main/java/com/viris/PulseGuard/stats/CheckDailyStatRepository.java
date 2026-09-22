package com.viris.PulseGuard.stats;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CheckDailyStatRepository extends JpaRepository<CheckDailyStat, CheckDailyStatId> {

    List<CheckDailyStat> findByIdMonitorIdAndIdDayBetweenOrderByIdDay(Long monitorId, LocalDate from, LocalDate to);
}
