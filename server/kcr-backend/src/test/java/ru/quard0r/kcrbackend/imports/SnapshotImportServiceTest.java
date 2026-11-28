package ru.quard0r.kcrbackend.imports;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import ru.quard0r.kcrbackend.clients.events.ClientEventRepository;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.clients.repository.ClientRepository;
import ru.quard0r.kcrbackend.imports.dto.SnapshotImportPreview;
import ru.quard0r.kcrbackend.imports.dto.SnapshotImportReport;
import ru.quard0r.kcrbackend.imports.dto.SnapshotPreviewRow;
import ru.quard0r.kcrbackend.snapshots.DynamicsService;
import ru.quard0r.kcrbackend.snapshots.model.DocumentTransferSnapshot;
import ru.quard0r.kcrbackend.snapshots.repository.DocumentTransferSnapshotRepository;
import ru.quard0r.kcrbackend.users.model.Role;
import ru.quard0r.kcrbackend.users.model.User;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SnapshotImportServiceTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2026, 10, 20);
    private static final String PERIOD = "1 кв 2026";
    private static final List<String> FILE_CODES = List.of("AAA11111", "BBB22222", "CCC33333");
    private static final List<Long> MATCHED_IDS = List.of(1L, 2L);

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private DocumentTransferSnapshotRepository snapshotRepository;

    @Mock
    private ClientEventRepository eventRepository;

    @Mock
    private DynamicsService dynamicsService;

    @Captor
    private ArgumentCaptor<List<DocumentTransferSnapshot>> savedSnapshots;

    @InjectMocks
    private SnapshotImportService importService;

    private final Client first = client(1L, "AAA11111");
    private final Client second = client(2L, "BBB22222");
    private final User author = User.builder()
            .id(9L).username("admin").password("password123").role(Role.ADMIN).build();

    @Test
    void previewInheritsMissingTotalWithOneHistoryQuery() throws IOException
    {
        givenMatchedClients();
        givenFirstClientHistory();

        SnapshotImportPreview preview = importService.preview(file(false), SNAPSHOT_DATE, PERIOD);

        assertEquals(List.of("AAA11111", "BBB22222"),
                preview.rows().stream().map(SnapshotPreviewRow::kcrCode).toList());
        assertEquals(List.of("CCC33333"), preview.unmatchedKcrCodes());
        assertNull(preview.rows().get(0).totalInFile());
        assertEquals(80, preview.rows().get(0).totalResolved());
        assertNull(preview.rows().get(1).totalResolved());
        verify(snapshotRepository).findByClientIdInOrderByClientIdAscSnapshotDateAscIdAsc(MATCHED_IDS);
    }

    @Test
    void previewDoesNotLoadHistoryWhenFileCarriesTotal() throws IOException
    {
        givenMatchedClients();

        SnapshotImportPreview preview = importService.preview(file(true), SNAPSHOT_DATE, PERIOD);

        assertEquals(3419, preview.rows().get(0).totalInFile());
        assertEquals(3419, preview.rows().get(0).totalResolved());
        assertEquals(2928, preview.rows().get(1).totalResolved());
        verifyNoInteractions(snapshotRepository);
    }

    @Test
    void applyInheritsLatestTotalOfSamePeriod() throws IOException
    {
        givenMatchedClients();
        givenFirstClientHistory();

        SnapshotImportReport report = importService.apply(file(false), SNAPSHOT_DATE, PERIOD, author);

        assertEquals(2, report.created());
        assertEquals(List.of("CCC33333"), report.unmatchedKcrCodes());
        verify(snapshotRepository).findByClientIdInOrderByClientIdAscSnapshotDateAscIdAsc(MATCHED_IDS);
        verify(snapshotRepository).saveAll(savedSnapshots.capture());
        assertEquals(80, savedSnapshots.getValue().get(0).getTotalDocuments());
        assertNull(savedSnapshots.getValue().get(1).getTotalDocuments());
    }

    private void givenMatchedClients()
    {
        when(clientRepository.findByKcrCodeIn(FILE_CODES)).thenReturn(List.of(first, second));
    }

    private void givenFirstClientHistory()
    {
        when(snapshotRepository.findByClientIdInOrderByClientIdAscSnapshotDateAscIdAsc(MATCHED_IDS))
                .thenReturn(List.of(
                        snapshot(11L, first, PERIOD, LocalDate.of(2026, 4, 10), 96),
                        snapshot(12L, first, PERIOD, LocalDate.of(2026, 7, 10), 80),
                        snapshot(13L, first, "2 кв 2026", LocalDate.of(2026, 8, 10), 999)));
    }

    private Client client(Long id, String kcrCode)
    {
        return Client.builder()
                .id(id).kcrCode(kcrCode).inn("1234567890").organizationName("Organization " + id)
                .build();
    }

    private DocumentTransferSnapshot snapshot(Long id, Client client, String reportingPeriod,
                                              LocalDate snapshotDate, Integer totalDocuments)
    {
        return DocumentTransferSnapshot.builder()
                .id(id).client(client).reportingPeriod(reportingPeriod).snapshotDate(snapshotDate)
                .totalDocuments(totalDocuments).notTransferredDocuments(1)
                .build();
    }

    private MultipartFile file(boolean withTotalColumn) throws IOException
    {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream())
        {
            Sheet sheet = workbook.createSheet(PERIOD);
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("Уникальны обезличинный идентификатор");
            if (withTotalColumn)
            {
                header.createCell(3).setCellValue("Документов всего");
            }
            header.createCell(4).setCellValue("Всего не отправлено документов");
            row(sheet, 2, "AAA11111", withTotalColumn ? 3419 : null, 12);
            row(sheet, 3, "BBB22222", withTotalColumn ? 2928 : null, 7);
            row(sheet, 4, "CCC33333", withTotalColumn ? 3100 : null, 5);
            workbook.write(out);
            return new MockMultipartFile("file", out.toByteArray());
        }
    }

    private void row(Sheet sheet, int rowIndex, String kcrCode, Integer totalDocuments,
                     int notTransferredDocuments)
    {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(kcrCode);
        if (totalDocuments != null)
        {
            row.createCell(3).setCellValue(totalDocuments);
        }
        row.createCell(4).setCellValue(notTransferredDocuments);
    }
}
