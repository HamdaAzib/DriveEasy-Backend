package com.driveeasy.config;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

@EnableConfigServer
@SpringBootApplication
public class ConfigServerApplication {

    public static void main(String[] args) throws IOException, URISyntaxException {
        Path configRepo = locateConfigRepo();
        SpringApplication application = new SpringApplication(ConfigServerApplication.class);
        application.setDefaultProperties(Map.of(
                "spring.cloud.config.server.native.search-locations", configRepo.toUri().toString()));
        application.run(args);
    }

    /** Finds the sibling config-repo directory from either the working directory or this class's location. */
    private static Path locateConfigRepo() throws IOException, URISyntaxException {
        String configuredPath = System.getenv("DRIVEEASY_CONFIG_REPO");
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path configured = Path.of(configuredPath).toAbsolutePath().normalize();
            if (Files.isDirectory(configured)) {
                return configured;
            }
            throw new IOException("DRIVEEASY_CONFIG_REPO does not point to a directory: " + configured);
        }

        List<Path> startingPoints = new ArrayList<>();
        startingPoints.add(Path.of(System.getProperty("user.dir")));
        startingPoints.add(Path.of(ConfigServerApplication.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()));

        for (Path start : startingPoints) {
            Path current = start.toAbsolutePath().normalize();
            while (current != null) {
                Path candidate = current.resolve("config-repo");
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
                current = current.getParent();
            }
        }
        throw new IOException("Could not find config-repo relative to the config-server project. "
                + "Set DRIVEEASY_CONFIG_REPO to its absolute path.");
    }
}
