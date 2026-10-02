package com.idem.checkin.provisioning;

import com.idem.checkin.session.StationSessionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Статусы записи NFC от Station Agent. Authorization: Bearer &lt;sessionToken&gt; той сессии, в которой выдан job
 * (даже если она уже закрыта — чтобы Agent мог дослать результат после перезапуска).
 */
@RestController
@RequestMapping("/api/v1/provisioning")
class ProvisioningController {

    private final StationSessionService sessions;
    private final ProvisioningService provisioning;
    private final ProvisioningRepository repository;
    private final RegistrationSync sync;

    ProvisioningController(StationSessionService sessions, ProvisioningService provisioning,
                           ProvisioningRepository repository, RegistrationSync sync) {
        this.sessions = sessions;
        this.provisioning = provisioning;
        this.repository = repository;
        this.sync = sync;
    }

    @PostMapping("/{jobId}/status")
    ProvisioningDtos.JobView report(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                    @PathVariable UUID jobId,
                                    @Valid @RequestBody ProvisioningDtos.StatusReport report) {
        var view = provisioning.report(sessions.authenticate(authorization), jobId, report);
        if (view.status() == JobStatus.COMPLETED) {
            repository.find(jobId).ifPresent(sync::trySync);
        }
        return view;
    }

    @GetMapping("/{jobId}")
    ProvisioningDtos.JobView get(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                 @PathVariable UUID jobId) {
        return provisioning.get(sessions.authenticate(authorization), jobId);
    }
}
