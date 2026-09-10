package com.fl.app.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.security")
public class AppSecurityProperties {

    private Jwt jwt = new Jwt();
    private Aes aes = new Aes();

    @Data
    public static class Jwt {
        /**
         * Secret key used for signing JWT tokens.
         */
        private String secret;

        /**
         * JWT token expiration time in seconds.
         */
        private long expirationSeconds;
    }

    @Data
    public static class Aes {
        /**
         * AES-256 key for model-weight encryption (32 bytes = 64 hex chars).
         */
        private String key;
    }
}
