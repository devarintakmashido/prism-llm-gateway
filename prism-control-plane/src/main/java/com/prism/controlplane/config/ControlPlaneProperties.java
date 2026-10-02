package com.prism.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "prism")
public record ControlPlaneProperties(String adminToken, Path dataDir) {
}
