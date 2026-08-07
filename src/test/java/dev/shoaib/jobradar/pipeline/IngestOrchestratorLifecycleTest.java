package dev.shoaib.jobradar.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.shoaib.jobradar.core.CompanyRegistry;
import dev.shoaib.jobradar.core.HttpFetcher;
import dev.shoaib.jobradar.core.JobPosting;
import dev.shoaib.jobradar.core.JobSourceAdapter;
import dev.shoaib.jobradar.core.JobStatus;
import dev.shoaib.jobradar.core.MatchEngine;
import dev.shoaib.jobradar.core.SourceFetchException;
import dev.shoaib.jobradar.core.SourceType;
import dev.shoaib.jobradar.core.persistence.IngestRunEntity;
import dev.shoaib.jobradar.core.persistence.IngestRunRepository;
import dev.shoaib.jobradar.core.persistence.JobRecordEntity;
import dev.shoaib.jobradar.core.persistence.JobRecordRepository;
import dev.shoaib.jobradar.core.persistence.MatchResultRepository;
import dev.shoaib.jobradar.notify.NotificationChannel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Covers Section 8's NEW -> ACTIVE -> REMOVED lifecycle and the "never mass-remove
 * on a failed/exceptioned fetch" rule, against mocked repositories (no real DB).
 */
class IngestOrchestratorLifecycleTest {

    private final JobRecordRepository jobRecordRepository = mock(JobRecordRepository.class);
    private final MatchResultRepository matchResultRepository = mock(MatchResultRepository.class);
    private final IngestRunRepository ingestRunRepository = mock(IngestRunRepository.class);
    private final MatchEngine matchEngine = mock(MatchEngine.class);
    private final HttpFetcher httpFetcher = mock(HttpFetcher.class);
    private final CompanyRegistry companyRegistry = mock(CompanyRegistry.class);

    @TempDir
    Path tempDir;

    @BeforeEach
    void commonStubs() {
        when(ingestRunRepository.save(any(IngestRunEntity.class))).thenAnswer(inv -> {
            IngestRunEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(1);
            }
            return e;
        });
        when(jobRecordRepository.save(any(JobRecordEntity.class))).thenAnswer(inv -> {
            JobRecordEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(100);
            }
            return e;
        });
        when(matchEngine.evaluate(any())).thenReturn(Optional.empty());
        when(matchResultRepository.findUnnotifiedByJobStatus(any())).thenReturn(List.of());
        when(matchResultRepository.search(any(), any(), any())).thenReturn(List.of());
    }

    private IngestOrchestrator newOrchestrator(List<JobSourceAdapter> adapters, List<NotificationChannel> channels) {
        IngestOrchestrator orchestrator = new IngestOrchestrator(adapters, httpFetcher, companyRegistry, matchEngine,
            jobRecordRepository, matchResultRepository, ingestRunRepository, channels);
        ReflectionTestUtils.setField(orchestrator, "dryRun", false);
        ReflectionTestUtils.setField(orchestrator, "exportDir", tempDir.toString());
        return orchestrator;
    }

    @Test
    void newPostingIsInsertedAsActive() throws Exception {
        JobSourceAdapter adapter = mock(JobSourceAdapter.class);
        when(adapter.name()).thenReturn("arbeitnow");
        when(adapter.enabled()).thenReturn(true);
        when(adapter.supportsRemovalDetection()).thenReturn(true);
        when(adapter.fetch(any())).thenReturn(List.of(samplePosting("arbeitnow", "ext-1")));
        when(jobRecordRepository.findBySourceAndExternalId("arbeitnow", "ext-1")).thenReturn(Optional.empty());
        when(jobRecordRepository.findBySourceAndStatus("arbeitnow", JobStatus.ACTIVE)).thenReturn(List.of());

        IngestOrchestrator orchestrator = newOrchestrator(List.of(adapter), List.of());
        orchestrator.runOnce();

        ArgumentCaptor<JobRecordEntity> captor = ArgumentCaptor.forClass(JobRecordEntity.class);
        verify(jobRecordRepository).save(captor.capture());
        JobRecordEntity saved = captor.getValue();
        assertThat(saved.getExternalId()).isEqualTo("ext-1");
        assertThat(saved.getStatus()).isEqualTo(JobStatus.ACTIVE);
        assertThat(saved.getFirstSeen()).isEqualTo(saved.getLastSeen());
    }

    @Test
    void postingMissingFromCurrentFetchIsMarkedRemoved() throws Exception {
        JobSourceAdapter adapter = mock(JobSourceAdapter.class);
        when(adapter.name()).thenReturn("arbeitnow");
        when(adapter.enabled()).thenReturn(true);
        when(adapter.supportsRemovalDetection()).thenReturn(true);
        // Nothing comes back this run -- the previously-active job has disappeared.
        when(adapter.fetch(any())).thenReturn(List.of());

        JobRecordEntity existing = new JobRecordEntity();
        existing.setId(5);
        existing.setSource("arbeitnow");
        existing.setExternalId("ext-gone");
        existing.setStatus(JobStatus.ACTIVE);
        existing.setTitle("Backend Engineer");
        existing.setUrl("https://example.com/gone");
        existing.setFirstSeen(Instant.now().toString());
        existing.setLastSeen(Instant.now().toString());
        when(jobRecordRepository.findBySourceAndStatus("arbeitnow", JobStatus.ACTIVE)).thenReturn(List.of(existing));

        IngestOrchestrator orchestrator = newOrchestrator(List.of(adapter), List.of());
        orchestrator.runOnce();

        ArgumentCaptor<JobRecordEntity> captor = ArgumentCaptor.forClass(JobRecordEntity.class);
        verify(jobRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getExternalId()).isEqualTo("ext-gone");
        assertThat(captor.getValue().getStatus()).isEqualTo(JobStatus.REMOVED);
    }

    @Test
    void reappearingRemovedPostingIsReactivatedToActive() throws Exception {
        JobSourceAdapter adapter = mock(JobSourceAdapter.class);
        when(adapter.name()).thenReturn("arbeitnow");
        when(adapter.enabled()).thenReturn(true);
        when(adapter.supportsRemovalDetection()).thenReturn(true);
        JobPosting posting = samplePosting("arbeitnow", "ext-back");
        when(adapter.fetch(any())).thenReturn(List.of(posting));

        JobRecordEntity existing = new JobRecordEntity();
        existing.setId(7);
        existing.setSource("arbeitnow");
        existing.setExternalId("ext-back");
        existing.setStatus(JobStatus.REMOVED);
        existing.setTitle("old title");
        existing.setUrl("https://example.com/back");
        existing.setFirstSeen(Instant.now().toString());
        existing.setLastSeen(Instant.now().toString());
        when(jobRecordRepository.findBySourceAndExternalId("arbeitnow", "ext-back")).thenReturn(Optional.of(existing));
        when(jobRecordRepository.findBySourceAndStatus("arbeitnow", JobStatus.ACTIVE)).thenReturn(List.of());

        IngestOrchestrator orchestrator = newOrchestrator(List.of(adapter), List.of());
        orchestrator.runOnce();

        ArgumentCaptor<JobRecordEntity> captor = ArgumentCaptor.forClass(JobRecordEntity.class);
        verify(jobRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(JobStatus.ACTIVE);
    }

    @Test
    void fetchFailureNeverTriggersMassRemovalForThatSource() throws Exception {
        JobSourceAdapter adapter = mock(JobSourceAdapter.class);
        when(adapter.name()).thenReturn("arbeitnow");
        when(adapter.enabled()).thenReturn(true);
        when(adapter.supportsRemovalDetection()).thenReturn(true);
        when(adapter.fetch(any())).thenThrow(new SourceFetchException("network blip"));

        IngestOrchestrator orchestrator = newOrchestrator(List.of(adapter), List.of());
        orchestrator.runOnce();

        verify(jobRecordRepository, never()).findBySourceAndStatus(eq("arbeitnow"), any());
        verify(jobRecordRepository, never()).save(any());
    }

    @Test
    void adapterWithoutRemovalSupportIsNeverUsedForRemoval() throws Exception {
        JobSourceAdapter adapter = mock(JobSourceAdapter.class);
        when(adapter.name()).thenReturn("hn-who-is-hiring");
        when(adapter.enabled()).thenReturn(true);
        when(adapter.supportsRemovalDetection()).thenReturn(false);
        when(adapter.fetch(any())).thenReturn(List.of());

        IngestOrchestrator orchestrator = newOrchestrator(List.of(adapter), List.of());
        orchestrator.runOnce();

        verify(jobRecordRepository, never()).findBySourceAndStatus(anyString(), any());
    }

    private JobPosting samplePosting(String source, String externalId) {
        return new JobPosting(SourceType.ARBEITNOW, source, externalId, "Backend Engineer", "Acme",
            "Berlin", "DE", "https://example.com/job/" + externalId, "Java and Spring Boot role",
            List.of("Java", "Spring"), List.of("Relocation support"), "60k-80k", true, Instant.now());
    }
}
