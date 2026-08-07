package dev.shoaib.jobradar.core;

/**
 * Implemented by pipeline (a single Spring bean, discovered by web via autowiring)
 * so POST /api/run can trigger the same ingest pass the hourly scheduler runs.
 */
public interface IngestRunner {

    /** Runs one full ingest pass synchronously and returns the new ingest_run id. */
    Integer runOnce();
}
