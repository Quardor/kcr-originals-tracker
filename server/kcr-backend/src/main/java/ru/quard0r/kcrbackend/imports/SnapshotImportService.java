package ru.quard0r.kcrbackend.imports;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.quard0r.kcrbackend.exception.DomainException;
import ru.quard0r.kcrbackend.exception.ErrorCode;
import ru.quard0r.kcrbackend.imports.dto.SnapshotCreateCommand;
import ru.quard0r.kcrbackend.imports.dto.SnapshotImportPreview;
import ru.quard0r.kcrbackend.imports.dto.SnapshotImportReport;
import ru.quard0r.kcrbackend.imports.dto.SnapshotPreviewRow;
import ru.quard0r.kcrbackend.model.Client;
import ru.quard0r.kcrbackend.model.DocumentTransferSnapshot;
import ru.quard0r.kcrbackend.repository.ClientRepository;
import ru.quard0r.kcrbackend.repository.DocumentTransferSnapshotRepository;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Snapshot import over the Excel adapter (ARCHITECTURE.md §11.8 item 3): the caller selects
 * the reporting period (ТЗ scenario 2) and only matching sheets are imported, snapshots are
 * created only for rows whose KCR code matches an existing client; unmatched codes are
 * reported, never failed. The real export carries only the "not transferred" counter — the
 * total is inherited from the latest snapshot of the same client and period
 * (ARCHITECTURE.md §4). Recalculation of dynamics categories and IMPORT events land with
 * V6/client_events.
 */
@Service
@RequiredArgsConstructor
public class SnapshotImportService {

    private final ClientRepository clientRepository;
    private final DocumentTransferSnapshotRepository snapshotRepository;
    private final SnapshotExcelParser parser = new SnapshotExcelParser();

    public SnapshotImportPreview preview(MultipartFile file, LocalDate snapshotDate, String reportingPeriod) {
        ParseResult<SnapshotCreateCommand> result = parse(file, snapshotDate, reportingPeriod);
        Map<String, Client> clients = loadClients(result.getCommands());

        List<SnapshotPreviewRow> rows = new ArrayList<>();
        List<String> unmatchedKcrCodes = new ArrayList<>();
        List<ParseIssue> issues = new ArrayList<>(result.getIssues());
        for (SnapshotCreateCommand command : result.getCommands()) {
            Client client = clients.get(command.kcrCode());
            if (client == null) {
                unmatchedKcrCodes.add(command.kcrCode());
                continue;
            }
            rows.add(new SnapshotPreviewRow(command.kcrCode(), command.reportingPeriod(),
                    command.snapshotDate(), command.totalDocuments(),
                    resolveTotal(client, command), command.notTransferredDocuments()));
        }
        return new SnapshotImportPreview(rows, unmatchedKcrCodes, result.getSkippedSheets(), issues);
    }

    @Transactional
    public SnapshotImportReport apply(MultipartFile file, LocalDate snapshotDate, String reportingPeriod) {
        ParseResult<SnapshotCreateCommand> result = parse(file, snapshotDate, reportingPeriod);
        Map<String, Client> clients = loadClients(result.getCommands());

        int created = 0;
        List<String> unmatchedKcrCodes = new ArrayList<>();
        List<ParseIssue> issues = new ArrayList<>(result.getIssues());
        for (SnapshotCreateCommand command : result.getCommands()) {
            Client client = clients.get(command.kcrCode());
            if (client == null) {
                unmatchedKcrCodes.add(command.kcrCode());
                continue;
            }
            snapshotRepository.save(DocumentTransferSnapshot.builder()
                    .client(client)
                    .reportingPeriod(command.reportingPeriod())
                    .snapshotDate(command.snapshotDate())
                    .totalDocuments(resolveTotal(client, command))
                    .notTransferredDocuments(command.notTransferredDocuments())
                    .build());
            created++;
        }
        return new SnapshotImportReport(created, unmatchedKcrCodes, result.getSkippedSheets(), issues);
    }

    private ParseResult<SnapshotCreateCommand> parse(MultipartFile file, LocalDate snapshotDate,
                                                     String reportingPeriod) {
        if (file == null || file.isEmpty()) {
            throw new DomainException(ErrorCode.IMPORT_FILE_REQUIRED);
        }
        if (reportingPeriod == null || reportingPeriod.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        ParseResult<SnapshotCreateCommand> result;
        try {
            result = parser.parse(file.getInputStream(), snapshotDate, reportingPeriod);
        } catch (IOException | RuntimeException e) {
            throw new DomainException(ErrorCode.IMPORT_FILE_UNREADABLE);
        }
        if (result.getCommands().isEmpty() && result.getIssues().isEmpty()
                && !result.getSkippedSheets().isEmpty()) {
            throw new DomainException(ErrorCode.IMPORT_PERIOD_NOT_FOUND);
        }
        return result;
    }

    private Map<String, Client> loadClients(List<SnapshotCreateCommand> commands) {
        if (commands.isEmpty()) {
            return Map.of();
        }
        return clientRepository.findByKcrCodeIn(commands.stream().map(SnapshotCreateCommand::kcrCode).toList())
                .stream()
                .collect(Collectors.toMap(Client::getKcrCode, Function.identity()));
    }

    private Integer resolveTotal(Client client, SnapshotCreateCommand command) {
        if (command.totalDocuments() != null) {
            return command.totalDocuments();
        }
        return snapshotRepository
                .findFirstByClientIdAndReportingPeriodOrderBySnapshotDateDescIdDesc(
                        client.getId(), command.reportingPeriod())
                .map(DocumentTransferSnapshot::getTotalDocuments)
                .orElse(null);
    }
}
