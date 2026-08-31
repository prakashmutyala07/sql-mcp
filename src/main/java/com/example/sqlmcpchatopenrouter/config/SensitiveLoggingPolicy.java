package com.example.sqlmcpchatopenrouter.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Gates raw PII logging behind both an explicit property and a local/dev profile.
 */
@Component
public class SensitiveLoggingPolicy {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveLoggingPolicy.class);

    private static final Set<String> ALLOWED_PROFILES = Set.of("local", "dev");

    private final AppProperties properties;

    private final Set<String> activeProfiles;

    public SensitiveLoggingPolicy(AppProperties properties, Environment environment) {
        this.properties = properties;
        this.activeProfiles = Arrays.stream(environment.getActiveProfiles()).collect(Collectors.toUnmodifiableSet());
    }

    @PostConstruct
    void warnIfSensitiveLoggingWasRejected() {
        if (requestedSensitiveLogging() && !isLocalOrDevProfile()) {
            logger.warn("[STEP 3 - PII PROTECTION] app.logging.log-sensitive-data=true was ignored because "
                    + "activeProfiles={} does not include local/dev", this.activeProfiles);
        }
        else if (sensitiveLoggingEnabled()) {
            logger.warn("[STEP 3 - PII PROTECTION] LOCAL SENSITIVE DEBUG LOGGING ENABLED. Raw business PII may "
                    + "appear in stage logs; secrets, credentials, and authorization data remain excluded.");
        }
    }

    public boolean sensitiveLoggingEnabled() {
        return requestedSensitiveLogging() && isLocalOrDevProfile();
    }

    private boolean requestedSensitiveLogging() {
        return this.properties.logging() != null && this.properties.logging().logSensitiveData();
    }

    private boolean isLocalOrDevProfile() {
        return this.activeProfiles.stream().anyMatch(ALLOWED_PROFILES::contains);
    }
}
