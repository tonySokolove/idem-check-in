package com.idem.checkin.provisioning;

import com.idem.checkin.CheckInProperties;
import com.idem.checkin.api.ApiException;
import com.idem.checkin.provisioning.ProvisioningDtos.JobView;
import com.idem.checkin.provisioning.ProvisioningDtos.StatusReport;
import com.idem.checkin.security.Secrets;
import com.idem.checkin.session.StationSession;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ProvisioningService {

    private final ProvisioningRepository repository;
    private final Secrets secrets;
    private final CheckInProperties properties;
    private final Clock clock;

    public ProvisioningService(ProvisioningRepository repository, Secrets secrets,
                               CheckInProperties properties, Clock clock) {
        this.repository = repository;
        this.secrets = secrets;
        this.properties = properties;
        this.clock = clock;
    }

    /** Результат попытки выдать задание на запись. */
    public sealed interface StartResult {
        record Started(ProvisioningJob job) implements StartResult {
        }

        /** У гостя уже есть активный бейдж. */
        record AlreadyIssued() implements StartResult {
        }

        /** По этой регистрации уже идёт запись; job отдаётся, только если это та же стойка. */
        record InProgress(ProvisioningJob job, boolean sameStation) implements StartResult {
        }

        /** На этой стойке ещё не закончен другой job. */
        record StationBusy(UUID activeJobId) implements StartResult {
        }
    }

    @Transactional
    public StartResult start(StationSession session, UUID registrationId, String correlationId) {
        Instant now = clock.instant();
        // Порядок блокировок всегда «регистрация → стойка», чтобы не было взаимных блокировок
        repository.lock("registration:" + registrationId);
        repository.lock("session:" + session.id());
        repository.expireStale(registrationId, session.id(), now);

        if (repository.hasActiveBadge(registrationId)) {
            return new StartResult.AlreadyIssued();
        }
        var inProgress = repository.findActiveByRegistration(registrationId);
        if (inProgress.isPresent()) {
            var job = inProgress.get();
            return new StartResult.InProgress(job, job.stationSessionId().equals(session.id()));
        }
        var busy = repository.findActiveBySession(session.id());
        if (busy.isPresent()) {
            return new StartResult.StationBusy(busy.get().id());
        }

        UUID badgeId = UUID.randomUUID();
        String token = secrets.publicCardToken();
        String payload = properties.cardBaseUrl() + token;
        UUID jobId = UUID.randomUUID();
        try {
            repository.insertBadge(badgeId, registrationId, session.eventId(), token, now);
            repository.insertJob(jobId, session.id(), registrationId, badgeId, payload, correlationId,
                    now, now.plus(properties.provisioningJobTtl()));
        } catch (DuplicateKeyException race) {
            // Защищено блокировками выше; уникальные индексы — последняя линия обороны
            throw new ApiException(HttpStatus.CONFLICT, "PROVISIONING_IN_PROGRESS", "Provisioning already in progress");
        }
        return new StartResult.Started(repository.find(jobId).orElseThrow());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public JobView get(StationSession session, UUID jobId) {
        repository.expireJob(jobId, clock.instant());
        return JobView.of(own(session, jobId));
    }

    /**
     * Принимает статус от Agent. Повторы и статусы «из прошлого» не ломают состояние.
     * COMPLETED принимается только с прочитанным обратно payload, совпадающим с выданным.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public JobView report(StationSession session, UUID jobId, StatusReport report) {
        Instant now = clock.instant();
        JobStatus requested = report.status();
        if (!JobStatus.REPORTABLE.contains(requested)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "status must be one of " + JobStatus.REPORTABLE);
        }
        own(session, jobId);
        repository.lock("registration:" + repository.find(jobId).orElseThrow().registrationId());
        // Истечение срока проверяется до того, как Hub перейдёт к записи: после expiresAt писать уже нельзя
        if (requested != JobStatus.COMPLETED && requested != JobStatus.FAILED && requested != JobStatus.CANCELLED) {
            repository.expireJob(jobId, now);
        }
        ProvisioningJob job = repository.find(jobId).orElseThrow();
        JobStatus current = job.status();

        if (requested == current) {
            return JobView.of(job);
        }
        if (current == JobStatus.COMPLETED) {
            throw conflict("JOB_ALREADY_COMPLETED", "Job is already completed", job);
        }
        if (current == JobStatus.FAILED || current == JobStatus.CANCELLED) {
            throw conflict("JOB_ALREADY_FINISHED", "Job is already " + current, job);
        }
        if (current == JobStatus.TIMED_OUT && requested != JobStatus.COMPLETED) {
            throw conflict("JOB_TIMED_OUT", "Job timed out; start a new check-in", job);
        }

        switch (requested) {
            case COMPLETED -> complete(job, report.readBackPayload(), now);
            case FAILED, CANCELLED -> {
                var error = report.error();
                repository.updateStatus(jobId, requested,
                        error == null || error.code() == null ? requested.name() : error.code(),
                        error == null ? null : error.message(), now);
                repository.setBadgeStatus(job.badgeId(), "DISCARDED", now);
            }
            default -> {
                // Статусы прогресса могут прийти не по порядку — назад не откатываемся
                if (requested.progress() > current.progress()) {
                    repository.updateStatus(jobId, requested, null, null, now);
                }
            }
        }
        return JobView.of(repository.find(jobId).orElseThrow());
    }

    private void complete(ProvisioningJob job, String readBack, Instant now) {
        if (readBack == null || !readBack.equals(job.payload())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VERIFICATION_MISMATCH",
                    "readBackPayload must equal the issued payload; report FAILED if the tag could not be verified");
        }
        if (repository.hasActiveBadge(job.registrationId())) {
            // Гостю уже активировали другой бейдж (например, по повторному check-in) — этот недействителен
            repository.updateStatus(job.id(), JobStatus.FAILED, "BADGE_ALREADY_ISSUED",
                    "Another badge is already active for this registration", now);
            repository.setBadgeStatus(job.badgeId(), "DISCARDED", now);
            return;
        }
        // В том числе «поздний» COMPLETED после TIMED_OUT: метка уже записана, бейдж стоит активировать
        repository.setBadgeStatus(job.badgeId(), "ACTIVE", now);
        repository.updateStatus(job.id(), JobStatus.COMPLETED, null, null, now);
    }

    private ProvisioningJob own(StationSession session, UUID jobId) {
        return repository.find(jobId)
                .filter(job -> job.stationSessionId().equals(session.id()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Provisioning job not found"));
    }

    private static ApiException conflict(String code, String message, ProvisioningJob job) {
        return new ApiException(HttpStatus.CONFLICT, code, message, Map.of("job", JobView.of(job)));
    }
}
