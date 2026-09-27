package com.viris.PulseGuard.check;

import com.viris.PulseGuard.check.dto.RecentCheckRow;
import com.viris.PulseGuard.check.dto.UptimeRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface CheckRepository extends JpaRepository<Check, Long> {

    List<Check> findByMonitorIdOrderByCheckedAtDesc(Long monitorId, Pageable pageable);

    List<Check> findByMonitorIdAndCheckedAtBetween(Long monitorId, Instant from, Instant to);

    /** Check history in a time range; the page carries both the limit and the sort direction. */
    List<Check> findByMonitorIdAndCheckedAtBetween(Long monitorId, Instant from, Instant to, Pageable pageable);

    /** Oldest first, bounded: the incident timeline. Served by the (monitor_id, checked_at) index. */
    List<Check> findByMonitorIdAndCheckedAtBetweenOrderByCheckedAtAsc(Long monitorId, Instant from, Instant to,
                                                                       Pageable pageable);

    /**
     * 24h uptime for every monitor of one user in a single grouped query, instead of one
     * query per monitor. Uses the (monitor_id, checked_at) index for the time range.
     */
    @Query("""
            select new com.viris.PulseGuard.check.dto.UptimeRow(
                c.monitor.id,
                count(c),
                sum(case when c.result = com.viris.PulseGuard.enumeration.CheckResult.UP then 1L else 0L end))
            from Check c
            where c.monitor.user.id = :userId and c.checkedAt >= :since
            group by c.monitor.id
            """)
    List<UptimeRow> uptimeSince(@Param("userId") Long userId, @Param("since") Instant since);

    /**
     * The latest {@code perMonitor} checks of every monitor of one user, oldest first within
     * each monitor, in one query. LATERAL runs the inner "newest N for this monitor" subquery
     * once per monitor, and each run is a short walk down the (monitor_id, checked_at DESC)
     * index, so the cost is monitors x N rows, never the whole checks table.
     * Aliases are quoted: Postgres folds unquoted names to lower case, and the projection
     * matches by name.
     */
    @Query(nativeQuery = true, value = """
            select r.monitor_id as "monitorId", r.result as "result",
                   r.status_code as "statusCode", r.response_time_ms as "responseTimeMs"
            from monitors m
            cross join lateral (
                select c.monitor_id, c.result, c.status_code, c.response_time_ms, c.checked_at
                from checks c
                where c.monitor_id = m.id
                order by c.checked_at desc
                limit :perMonitor
            ) r
            where m.user_id = :userId
            order by r.monitor_id, r.checked_at
            """)
    List<RecentCheckRow> recentChecksPerMonitor(@Param("userId") Long userId, @Param("perMonitor") int perMonitor);

    /**
     * Card figures for one window, computed by Postgres: nothing but one row comes back.
     * {@code FILTER} restricts an aggregate to some rows; response times count passing checks
     * only. {@code percentile_cont(0.95)} is the exact 95th percentile. CAST instead of
     * {@code ::} so Hibernate's parameter parsing never trips over the colons.
     */
    @Query(nativeQuery = true, value = """
            select count(*) as "total",
                   count(*) filter (where c.result = 'UP') as "up",
                   cast(avg(c.response_time_ms) filter (where c.result = 'UP') as float8) as "avgMs",
                   percentile_cont(0.95) within group (order by c.response_time_ms)
                       filter (where c.result = 'UP') as "p95Ms"
            from checks c
            where c.monitor_id = :monitorId and c.checked_at >= :windowStart and c.checked_at < :windowEnd
            """)
    WindowTotalsRow windowTotals(@Param("monitorId") Long monitorId,
                                 @Param("windowStart") Instant windowStart,
                                 @Param("windowEnd") Instant windowEnd);

    /**
     * The same totals per chart bucket. A check's bucket number is its offset from the window
     * start divided by the bucket size, so buckets line up with the window exactly. Only
     * buckets that contain checks come back; the caller fills the gaps.
     */
    @Query(nativeQuery = true, value = """
            select cast(floor(extract(epoch from (c.checked_at - :windowStart)) / :bucketSeconds) as int) as "idx",
                   count(*) as "total",
                   count(*) filter (where c.result = 'UP') as "up",
                   cast(avg(c.response_time_ms) filter (where c.result = 'UP') as float8) as "avgMs"
            from checks c
            where c.monitor_id = :monitorId and c.checked_at >= :windowStart and c.checked_at < :windowEnd
            group by 1
            order by 1
            """)
    List<BucketTotalsRow> bucketTotals(@Param("monitorId") Long monitorId,
                                       @Param("windowStart") Instant windowStart,
                                       @Param("windowEnd") Instant windowEnd,
                                       @Param("bucketSeconds") long bucketSeconds);

    /**
     * {@link #bucketTotals} for several monitors in one query: the public status page's daily
     * bars. One grouped scan per page view instead of one query per monitor; each monitor's
     * time range is still served by the (monitor_id, checked_at) index.
     */
    @Query(nativeQuery = true, value = """
            select c.monitor_id as "monitorId",
                   cast(floor(extract(epoch from (c.checked_at - :windowStart)) / :bucketSeconds) as int) as "idx",
                   count(*) as "total",
                   count(*) filter (where c.result = 'UP') as "up"
            from checks c
            where c.monitor_id in (:monitorIds) and c.checked_at >= :windowStart and c.checked_at < :windowEnd
            group by 1, 2
            """)
    List<MonitorBucketRow> bucketTotalsForMonitors(@Param("monitorIds") Collection<Long> monitorIds,
                                                   @Param("windowStart") Instant windowStart,
                                                   @Param("windowEnd") Instant windowEnd,
                                                   @Param("bucketSeconds") long bucketSeconds);

    interface MonitorBucketRow {
        Long getMonitorId();
        Integer getIdx();
        Long getTotal();
        Long getUp();
    }

    interface WindowTotalsRow {
        Long getTotal();
        Long getUp();
        Double getAvgMs();
        Double getP95Ms();
    }

    interface BucketTotalsRow {
        Integer getIdx();
        Long getTotal();
        Long getUp();
        Double getAvgMs();
    }

    /** Retention job: delete raw checks older than the cutoff. */
    @Modifying
    @Query("delete from Check c where c.checkedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
