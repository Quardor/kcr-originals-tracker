package ru.quard0r.kcrbackend.snapshots;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.snapshots.dto.ClientDebtInfo;
import ru.quard0r.kcrbackend.snapshots.model.DocumentTransferSnapshot;
import ru.quard0r.kcrbackend.snapshots.model.DynamicsCategory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DynamicsServiceTest
{
    private DynamicsService dynamicsService;

    @BeforeEach
    void setUp()
    {
        dynamicsService = new DynamicsService();
    }

    @Test
    void classify_noDebt_whenNotTransferredIsZero()
    {
        DocumentTransferSnapshot s = DocumentTransferSnapshot.builder()
                .id(1L)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 4, 15))
                .totalDocuments(10)
                .notTransferredDocuments(0)
                .build();

        DynamicsCategory category = dynamicsService.classifySnapshot(List.of(s), null);
        assertEquals(DynamicsCategory.NO_DEBT, category);
    }

    @Test
    void classify_newcomerRisk_whenClientCreatedLessThan6MonthsBeforeSnapshot()
    {
        Instant createdAt = LocalDate.of(2026, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        DocumentTransferSnapshot s = DocumentTransferSnapshot.builder()
                .id(1L)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 4, 15))
                .totalDocuments(10)
                .notTransferredDocuments(1)
                .build();

        DynamicsCategory category = dynamicsService.classifySnapshot(List.of(s), createdAt);
        assertEquals(DynamicsCategory.NEWCOMER_RISK, category);
    }

    @Test
    void classify_firstOccurrence_whenNoPriorDebtExisted()
    {
        Instant oldClientCreatedAt = LocalDate.of(2020, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        DocumentTransferSnapshot s1 = DocumentTransferSnapshot.builder()
                .id(1L)
                .reportingPeriod("2025-Q4")
                .snapshotDate(LocalDate.of(2026, 1, 15))
                .totalDocuments(10)
                .notTransferredDocuments(0)
                .build();
        DocumentTransferSnapshot s2 = DocumentTransferSnapshot.builder()
                .id(2L)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 4, 15))
                .totalDocuments(10)
                .notTransferredDocuments(3)
                .build();

        DynamicsCategory category = dynamicsService.classifySnapshot(List.of(s1, s2), oldClientCreatedAt);
        assertEquals(DynamicsCategory.FIRST_OCCURRENCE, category);
    }

    @Test
    void classify_decreasing_whenDebtIsLowerThanPreviousSnapshot()
    {
        Instant oldClientCreatedAt = LocalDate.of(2020, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        DocumentTransferSnapshot s1 = DocumentTransferSnapshot.builder()
                .id(1L)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 4, 15))
                .totalDocuments(10)
                .notTransferredDocuments(5)
                .build();
        DocumentTransferSnapshot s2 = DocumentTransferSnapshot.builder()
                .id(2L)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 5, 15))
                .totalDocuments(10)
                .notTransferredDocuments(3)
                .build();

        DynamicsCategory category = dynamicsService.classifySnapshot(List.of(s1, s2), oldClientCreatedAt);
        assertEquals(DynamicsCategory.DECREASING, category);
    }

    @Test
    void classify_reappeared_whenDebtExistedWasRepaidAndAppearedAgain()
    {
        Instant oldClientCreatedAt = LocalDate.of(2020, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        DocumentTransferSnapshot s1 = DocumentTransferSnapshot.builder()
                .id(1L)
                .reportingPeriod("2025-Q3")
                .snapshotDate(LocalDate.of(2025, 10, 15))
                .totalDocuments(10)
                .notTransferredDocuments(2)
                .build();
        DocumentTransferSnapshot s2 = DocumentTransferSnapshot.builder()
                .id(2L)
                .reportingPeriod("2025-Q4")
                .snapshotDate(LocalDate.of(2026, 1, 15))
                .totalDocuments(10)
                .notTransferredDocuments(0)
                .build();
        DocumentTransferSnapshot s3 = DocumentTransferSnapshot.builder()
                .id(3L)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 4, 15))
                .totalDocuments(10)
                .notTransferredDocuments(4)
                .build();

        DynamicsCategory category = dynamicsService.classifySnapshot(List.of(s1, s2, s3), oldClientCreatedAt);
        assertEquals(DynamicsCategory.REAPPEARED, category);
    }

    @Test
    void classify_persistent_whenDebtCarriedOverFromEarlierPeriodWithoutDecreasing()
    {
        Instant oldClientCreatedAt = LocalDate.of(2020, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        DocumentTransferSnapshot s1 = DocumentTransferSnapshot.builder()
                .id(1L)
                .reportingPeriod("2025-Q4")
                .snapshotDate(LocalDate.of(2026, 1, 15))
                .totalDocuments(10)
                .notTransferredDocuments(2)
                .build();
        DocumentTransferSnapshot s2 = DocumentTransferSnapshot.builder()
                .id(2L)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 4, 15))
                .totalDocuments(10)
                .notTransferredDocuments(2)
                .build();

        DynamicsCategory category = dynamicsService.classifySnapshot(List.of(s1, s2), oldClientCreatedAt);
        assertEquals(DynamicsCategory.PERSISTENT, category);
    }

    @Test
    void classify_unchangedOrGrowing_whenDebtSameOrGrewInSamePeriod()
    {
        Instant oldClientCreatedAt = LocalDate.of(2020, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        DocumentTransferSnapshot s1 = DocumentTransferSnapshot.builder()
                .id(1L)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 4, 15))
                .totalDocuments(10)
                .notTransferredDocuments(2)
                .build();
        DocumentTransferSnapshot s2 = DocumentTransferSnapshot.builder()
                .id(2L)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 5, 15))
                .totalDocuments(10)
                .notTransferredDocuments(4)
                .build();

        DynamicsCategory category = dynamicsService.classifySnapshot(List.of(s1, s2), oldClientCreatedAt);
        assertEquals(DynamicsCategory.UNCHANGED_OR_GROWING, category);
    }

    @Test
    void getClientDebtInfo_calculatesCorrectValues()
    {
        Client client = Client.builder()
                .id(100L)
                .kcrCode("ABC12345")
                .createdAt(LocalDate.of(2020, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC))
                .build();

        DocumentTransferSnapshot s1 = DocumentTransferSnapshot.builder()
                .id(1L)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 4, 15))
                .totalDocuments(20)
                .notTransferredDocuments(5)
                .build();
        DocumentTransferSnapshot s2 = DocumentTransferSnapshot.builder()
                .id(2L)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 5, 15))
                .totalDocuments(20)
                .notTransferredDocuments(5)
                .build();

        ClientDebtInfo debtInfo = dynamicsService.getClientDebtInfo(client, List.of(s1, s2));

        assertEquals(20, debtInfo.totalDocuments());
        assertEquals(5, debtInfo.notTransferredDocuments());
        assertEquals(25.0, debtInfo.percentNotTransferred());
        assertEquals(5, debtInfo.previousNotTransferred());
        assertEquals(DynamicsCategory.UNCHANGED_OR_GROWING, debtInfo.dynamicsCategory());
    }

    @Test
    void getClientDebtInfo_handlesEmptySnapshots()
    {
        Client client = Client.builder().id(100L).build();
        ClientDebtInfo debtInfo = dynamicsService.getClientDebtInfo(client, List.of());

        assertEquals(0, debtInfo.notTransferredDocuments());
        assertNull(debtInfo.totalDocuments());
        assertEquals(DynamicsCategory.NO_DEBT, debtInfo.dynamicsCategory());
    }
}
