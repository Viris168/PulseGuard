package com.viris.PulseGuard.stats;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface CheckDailyStatRepository extends JpaRepository<CheckDailyStat, CheckDailyStatId> {

    List<CheckDailyStat> findByIdMonitorIdAndIdDayBetweenOrderByIdDay(Long monitorId, LocalDate from, LocalDate to);

    /**
     * Summarises one UTC day of raw checks into one row per monitor. Safe to run again: a
     * re-run (or a check saved late) overwrites the day. Response times count passing checks
     * only, like the dashboard's own queries. CAST instead of {@code ::} so Hibernate's
     * parameter parsing never trips over the colons.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into check_daily_stats (monitor_id, day, total_checks, failed_checks, avg_response_ms, p95_response_ms)
            select c.monitor_id,
                   cast(:day as date),
                   count(*),
                   count(*) filter (where c.result = 'DOWN'),
                   cast(round(avg(c.response_time_ms) filter (where c.result = 'UP')) as int),
                   cast(round(cast(percentile_cont(0.95) within group (order by c.response_time_ms)
                       filter (where c.result = 'UP') as numeric)) as int)
            from checks c
            where c.checked_at >= :dayStart and c.checked_at < :dayEnd
            group by c.monitor_id
            on conflict (monitor_id, day) do update set
                total_checks = excluded.total_checks,
                failed_checks = excluded.failed_checks,
                avg_response_ms = excluded.avg_response_ms,
                p95_response_ms = excluded.p95_response_ms
            """)
    int rollUpDay(@Param("day") LocalDate day, @Param("dayStart") Instant dayStart, @Param("dayEnd") Instant dayEnd);

    /** The most recent summarised day across all monitors; null before the first rollup. */
    @Query("select max(s.id.day) from CheckDailyStat s")
    LocalDate latestDay();

    /** Retention: summaries of one plan's monitors older than {@code cutoff}. */
    @Modifying
    @Query(nativeQuery = true, value = """
            delete from check_daily_stats s
            using monitors m, users u
            where s.monitor_id = m.id and m.user_id = u.id
              and u.plan = :plan and s.day < :cutoff
            """)
    int deleteForPlanBefore(@Param("plan") String plan, @Param("cutoff") LocalDate cutoff);

    /** The public status page's past days, for several monitors in one query. */
    @Query(nativeQuery = true, value = """
            select s.monitor_id as "monitorId", s.day as "day",
                   s.total_checks as "total", s.failed_checks as "failed"
            from check_daily_stats s
            where s.monitor_id in (:monitorIds) and s.day >= :fromDay and s.day < :toDay
            """)
    List<DailyTotalsRow> dailyTotalsForMonitors(@Param("monitorIds") Collection<Long> monitorIds,
                                                @Param("fromDay") LocalDate fromDay,
                                                @Param("toDay") LocalDate toDay);

    interface DailyTotalsRow {
        Long getMonitorId();
        LocalDate getDay();
        Integer getTotal();
        Integer getFailed();
    }
}
