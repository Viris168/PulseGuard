package com.viris.PulseGuard;

import org.testcontainers.utility.DockerImageName;

/**
 * The Postgres image every Testcontainers test runs on, in one place. pgvector's image is
 * Postgres 16 plus the vector extension that V24 needs (AI_MILESTONE_3.md), matching
 * docker-compose.yml and deploy/docker-compose.yml.
 */
public final class TestDatabase {

    public static final DockerImageName IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    private TestDatabase() {
    }
}
