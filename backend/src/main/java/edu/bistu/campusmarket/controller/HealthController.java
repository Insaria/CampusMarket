package edu.bistu.campusmarket.controller;

import edu.bistu.campusmarket.security.ApiAccess;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @ApiAccess(ApiAccess.Requirement.PUBLIC)
    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
