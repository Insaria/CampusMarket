package edu.bistu.campusmarket.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm;

@Configuration(proxyBeanMethods = false)
public class PasswordConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        String format = "pbkdf2-sha256-v1";
        PasswordEncoder encoder = new Pbkdf2PasswordEncoder(
                "", 16, 600_000, SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        return new DelegatingPasswordEncoder(format, Map.of(format, encoder));
    }
}
