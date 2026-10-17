package com.example.jobs.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "worker.enabled", havingValue = "true", matchIfMissing = true)
public class WorkerScheduler {
    private final JobWorker jobWorker;

    public WorkerScheduler(JobWorker jobWorker) { this.jobWorker = jobWorker; }

    @Scheduled(fixedDelayString = "${worker.poll-interval-ms:1000}")
    public void poll() { jobWorker.pollOnce(); }
}
