package com.openpms.jobs;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** The recurring jobs by name. Each takes "now" as an argument, so tests and the dev endpoint can pick the moment. */
@Component
public class JobCatalog {
    /** What a job does at a given moment; the count goes to the log. */
    public interface Job {
        int run(Instant now) throws Exception;
    }

    private final Map<String, Job> jobs = new TreeMap<>();

    public JobCatalog(DateAlerts alerts, MailJobs mail, Housekeeping housekeeping, com.openpms.webhook.Webhooks webhooks,
                      com.openpms.itsm.SlaJobs slaJobs, com.openpms.itsm.PortalMail portalMail) {
        jobs.put("webhook-delivery", webhooks::deliver);
        jobs.put("date-alerts", alerts::run);
        jobs.put("mail-mention", mail::mentions);
        jobs.put("mail-digest", mail::digests);
        jobs.put("token-cleanup", housekeeping::tokens);
        jobs.put("file-sweep", housekeeping::files);
        jobs.put("sla-check", slaJobs::run);
        jobs.put("portal-mail", portalMail::run);
    }

    public Map<String, Job> all() {
        return jobs;
    }
}
