package com.viris.PulseGuard.notification;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Sends failed alerts whose retry is due. On the clustered Quartz store, so one node runs it
 * at a time; never throws, for the same reason as CheckJob: the next fire is the retry.
 */
@DisallowConcurrentExecution
public class AlertRetryJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(AlertRetryJob.class);

    private final NotificationService notificationService;

    public AlertRetryJob(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void execute(JobExecutionContext context) {
        try {
            int attempted = notificationService.retryDue(Instant.now());
            if (attempted > 0) {
                log.info("Retried {} failed alert(s)", attempted);
            }
        } catch (Exception e) {
            log.error("Alert retry sweep failed", e);
        }
    }
}
