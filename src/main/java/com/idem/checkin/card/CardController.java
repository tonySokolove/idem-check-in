package com.idem.checkin.card;

import com.idem.checkin.provisioning.ProvisioningRepository;
import com.idem.checkin.registration.RegistrationClient;
import com.idem.checkin.registration.RegistrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * ВРЕМЕННАЯ web-визитка по ссылке с NFC-бейджа, пока нет Participant &amp; Profile Service.
 * Показывает только то, что разрешено и при check-in (VISIBLE — имя, иначе ничего личного).
 */
@RestController
class CardController {

    private static final Logger log = LoggerFactory.getLogger(CardController.class);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    private final ProvisioningRepository badges;
    private final RegistrationClient registrations;

    CardController(ProvisioningRepository badges, RegistrationClient registrations) {
        this.badges = badges;
        this.registrations = registrations;
    }

    @GetMapping(value = "/c/{token}", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> card(@PathVariable String token) {
        Optional<RegistrationInfo> registration = Optional.of(token)
                .filter(t -> TOKEN.matcher(t).matches())
                .flatMap(badges::findRegistrationByActiveToken)
                .flatMap(id -> {
                    try {
                        return registrations.get(id);
                    } catch (RegistrationClient.RegistrationUnavailableException unavailable) {
                        log.warn("Card page for registration {} shown as inactive: {}", id, unavailable.getMessage());
                        return Optional.empty();
                    }
                });
        if (registration.isEmpty()) {
            log.debug("Card page: token is not bound to an active badge");
            return page(HttpStatus.NOT_FOUND, "Бейдж не активен", "Эта ссылка не привязана к действующему бейджу IDEM.");
        }
        RegistrationInfo info = registration.get();
        log.debug("Card page shown for registration {} ({})", info.registrationId(), info.visible() ? "VISIBLE" : "ANONYMOUS");
        String title = info.visible() ? HtmlUtils.htmlEscape(info.displayName()) : "Гость мероприятия";
        return page(HttpStatus.OK, title, "Визитка участника IDEM. Здесь появятся посещённые стенды и материалы.");
    }

    private static ResponseEntity<String> page(HttpStatus status, String title, String text) {
        String html = """
                <!doctype html>
                <html lang="ru">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <meta name="robots" content="noindex">
                  <title>IDEM</title>
                  <style>
                    body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
                         font-family:system-ui,sans-serif;background:#0b0f19;color:#fff}
                    main{width:min(480px,calc(100%% - 32px));padding:32px;border-radius:20px;background:#121827}
                    small{color:#67728d;letter-spacing:.08em;text-transform:uppercase}
                    h1{margin:12px 0 8px;font-size:28px}
                    p{margin:0;color:#939db5;line-height:1.5}
                  </style>
                </head>
                <body><main><small>IDEM</small><h1>%s</h1><p>%s</p></main></body>
                </html>
                """.formatted(title, text);
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'")
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .body(html);
    }
}
