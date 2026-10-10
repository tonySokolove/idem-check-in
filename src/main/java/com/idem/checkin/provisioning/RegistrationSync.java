package com.idem.checkin.provisioning;

import com.idem.checkin.registration.RegistrationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Отмечает check-in в Registration Service после успешной записи бейджа.
 * Если Registration недоступен, бейдж всё равно активен, а отметка повторяется фоново.
 */
@Component
public class RegistrationSync {

    private static final Logger log = LoggerFactory.getLogger(RegistrationSync.class);

    private final ProvisioningRepository repository;
    private final RegistrationClient registrations;

    public RegistrationSync(ProvisioningRepository repository, RegistrationClient registrations) {
        this.repository = repository;
        this.registrations = registrations;
    }

    public void trySync(ProvisioningJob job) {
        if (job.status() != JobStatus.COMPLETED || job.registrationSynced()) {
            return;
        }
        try {
            registrations.markCheckedIn(job.registrationId(), job.completedAt());
            repository.markRegistrationSynced(job.id());
            log.info("Registration {} marked as checked in (job {})", job.registrationId(), job.id());
        } catch (RuntimeException error) {
            log.warn("Check-in mark for job {} postponed: {}", job.id(), error.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${checkin.registration-sync-interval:PT30S}")
    void retryPending() {
        var pending = repository.findUnsynced(50);
        if (!pending.isEmpty()) {
            log.info("Retrying check-in mark in Registration for {} job(s)", pending.size());
        }
        pending.forEach(this::trySync);
    }
}
