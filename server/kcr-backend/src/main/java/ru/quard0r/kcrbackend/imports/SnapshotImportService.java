package ru.quard0r.kcrbackend.imports;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.quard0r.kcrbackend.common.error.DomainException;
import ru.quard0r.kcrbackend.common.error.ErrorCode;
import ru.quard0r.kcrbackend.imports.dto.SnapshotCreateCommand;
import ru.quard0r.kcrbackend.imports.dto.SnapshotImportPreview;
import ru.quard0r.kcrbackend.imports.dto.SnapshotImportReport;
import ru.quard0r.kcrbackend.clients.events.ClientEvent;
import ru.quard0r.kcrbackend.clients.events.ClientEventRepository;
import ru.quard0r.kcrbackend.clients.events.ClientEventType;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.clients.repository.ClientRepository;
import ru.quard0r.kcrbackend.imports.dto.SnapshotPreviewRow;
import ru.quard0r.kcrbackend.users.model.User;
import ru.quard0r.kcrbackend.snapshots.DynamicsService;
import ru.quard0r.kcrbackend.snapshots.model.DocumentTransferSnapshot;
import ru.quard0r.kcrbackend.snapshots.repository.DocumentTransferSnapshotRepository;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SnapshotImportService {

    private final ClientRepository clientRepository;
    private final DocumentTransferSnapshotRepository snapshotRepository;
    private final ClientEventRepository eventRepository;
    private final DynamicsService dynamicsService;
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
    public SnapshotImportReport apply(MultipartFile file, LocalDate snapshotDate,
                                      String reportingPeriod, User author) {
        ParseResult<SnapshotCreateCommand> result = parse(file, snapshotDate, reportingPeriod);
        Map<String, Client> clients = loadClients(result.getCommands());

        List<String> unmatchedKcrCodes = new ArrayList<>();
        List<ParseIssue> issues = new ArrayList<>(result.getIssues());

        List<SnapshotCreateCommand> matched = new ArrayList<>();
        for (SnapshotCreateCommand command : result.getCommands()) {
            if (clients.containsKey(command.kcrCode())) {
                matched.add(command);
            } else {
                unmatchedKcrCodes.add(command.kcrCode());
            }
        }

        if (matched.isEmpty()) {
            return new SnapshotImportReport(0, unmatchedKcrCodes, result.getSkippedSheets(), issues);
        }

        List<Long> clientIds = matched.stream()
                .map(c -> clients.get(c.kcrCode()).getId())
                .distinct()
                .toList();
        Map<Long, List<DocumentTransferSnapshot>> historyByClientId =
                snapshotRepository.findByClientIdInOrderByClientIdAscSnapshotDateAscIdAsc(clientIds)
                        .stream()
                        .collect(Collectors.groupingBy(s -> s.getClient().getId()));

        List<DocumentTransferSnapshot> toSave = new ArrayList<>(matched.size());
        List<ClientEvent> events = new ArrayList<>(matched.size());

        for (SnapshotCreateCommand command : matched) {
            Client client = clients.get(command.kcrCode());
            List<DocumentTransferSnapshot> existing =
                    historyByClientId.getOrDefault(client.getId(), List.of());

            Integer previousNotTransferred = existing.isEmpty() ? null
                    : existing.get(existing.size() - 1).getNotTransferredDocuments();

            DocumentTransferSnapshot snapshot = DocumentTransferSnapshot.builder()
                    .client(client)
                    .reportingPeriod(command.reportingPeriod())
                    .snapshotDate(command.snapshotDate())
                    .totalDocuments(resolveTotalFromHistory(existing, command))
                    .notTransferredDocuments(command.notTransferredDocuments())
                    .previousNotTransferred(previousNotTransferred)
                    .build();

            List<DocumentTransferSnapshot> history = new ArrayList<>(existing);
            history.add(snapshot);
            snapshot.setDynamicsCategory(dynamicsService.classifySnapshot(history, client.getCreatedAt()));

            toSave.add(snapshot);
            events.add(ClientEvent.builder()
                    .client(client)
                    .author(author)
                    .type(ClientEventType.IMPORT)
                    .comment("Импорт срезов (период «%s», срез %s): не передано %d"
                            .formatted(command.reportingPeriod(), command.snapshotDate(),
                                    command.notTransferredDocuments()))
                    .build());
        }

        snapshotRepository.saveAll(toSave);
        eventRepository.saveAll(events);

        return new SnapshotImportReport(toSave.size(), unmatchedKcrCodes, result.getSkippedSheets(), issues);
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

    private Integer resolveTotalFromHistory(List<DocumentTransferSnapshot> history,
                                            SnapshotCreateCommand command) {
        if (command.totalDocuments() != null) {
            return command.totalDocuments();
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            DocumentTransferSnapshot s = history.get(i);
            if (command.reportingPeriod().equals(s.getReportingPeriod())) {
                return s.getTotalDocuments();
            }
        }
        return null;
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
