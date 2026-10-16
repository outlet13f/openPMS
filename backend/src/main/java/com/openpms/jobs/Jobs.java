package com.openpms.jobs;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Recurring jobs on db-scheduler (architecture.md §4): one row per job in scheduled_tasks, so with several app
 * instances each run happens once. Started after the app is up, stopped (waiting for running jobs) on shutdown.
 * openpms.jobs.enabled=false turns them off (tests, or a web-only instance).
 */
@Component
@ConditionalOnProperty(name = "openpms.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class Jobs implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(Jobs.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final DataSource dataSource;
    private final Map<String, JobCatalog.Job> jobs;
    private final Map<String, Schedule> schedules;
    private Scheduler scheduler;

    public Jobs(DataSource dataSource, JobCatalog catalog) {
        this.dataSource = dataSource;
        this.jobs = catalog.all();
        // every hour (a person's "today" turns at their midnight), mentions every minute, digests every quarter hour
        this.schedules = Map.of(
                "date-alerts", Schedules.cron("0 5 * * * *", KST),
                "mail-mention", Schedules.fixedDelay(Duration.ofMinutes(1)),
                "mail-digest", Schedules.cron("0 */15 * * * *", KST),
                "token-cleanup", Schedules.cron("0 30 3 * * *", KST),
                "file-sweep", Schedules.cron("0 0 4 * * SUN", KST),
                // webhooks: what is due, every 15 seconds
                "webhook-delivery", Schedules.fixedDelay(Duration.ofSeconds(15)));
    }

    @Override
    public void start() {
        Function<String, RecurringTask<Void>> task = name -> Tasks.recurring(name, schedules.get(name)).execute((inst, ctx) -> {
            try {
                int n = jobs.get(name).run(Instant.now());
                if (n > 0) log.info("{}: {}", name, n);
            } catch (Exception e) {
                // db-scheduler records the failure and runs the job again at its next time
                throw new RuntimeException(name + " failed", e);
            }
        });
        List<RecurringTask<Void>> tasks = jobs.keySet().stream().map(task).toList();
        scheduler = Scheduler.create(dataSource).startTasks(tasks).threads(2).pollingInterval(Duration.ofSeconds(10)).build();
        scheduler.start();
    }

    @Override
    public void stop() {
        if (scheduler != null) scheduler.stop();
        scheduler = null;
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
