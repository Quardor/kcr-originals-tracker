package ru.quard0r.kcrbackend.imports;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotImportService {

    private final ClientRepository clientRepository;
    private final DocumentTransferSnapshotRepository snapshotRepository;
    private final ClientEventRepository eventRepository;
    private final DynamicsService dynamicsService;
    private final SnapshotExcelParser parser = new SnapshotExcelParser();

    @Transactional(readOnly = true)
    public SnapshotImportPreview preview(MultipartFile file, LocalDate snapshotDate, String reportingPeriod) {
        ParseResult<SnapshotCreateCommand> result = parse(file, snapshotDate, reportingPeriod);
        Map<String, Client> clients = loadClients(result.getCommands());
        Matching matching = matchClients(result.getCommands(), clients);

        List<SnapshotCreateCommand> inheritingTotal = matching.matched().stream()
                .filter(command -> command.totalDocuments() == null)
                .toList();
        Map<Long, List<DocumentTransferSnapshot>> historyByClientId = loadHistory(inheritingTotal, clients);

        List<SnapshotPreviewRow> rows = new ArrayList<>(matching.matched().size());
        for (SnapshotCreateCommand command : matching.matched()) {
            Client client = clients.get(command.kcrCode());
            List<DocumentTransferSnapshot> existing =
                    historyByClientId.getOrDefault(client.getId(), List.of());
            rows.add(new SnapshotPreviewRow(command.kcrCode(), command.reportingPeriod(),
                    command.snapshotDate(), command.totalDocuments(),
                    resolveTotalFromHistory(existing, command), command.notTransferredDocuments()));
        }
        return new SnapshotImportPreview(rows, matching.unmatchedKcrCodes(), result.getSkippedSheets(),
                result.getIssues());
    }

    @Transactional
    public SnapshotImportReport apply(MultipartFile file, LocalDate snapshotDate,
                                      String reportingPeriod, User author) {
        ParseResult<SnapshotCreateCommand> result = parse(file, snapshotDate, reportingPeriod);
        Map<String, Client> clients = loadClients(result.getCommands());
        Matching matching = matchClients(result.getCommands(), clients);
        if (matching.matched().isEmpty()) {
            return new SnapshotImportReport(0, matching.unmatchedKcrCodes(), result.getSkippedSheets(),
                    result.getIssues());
        }

        Map<Long, List<DocumentTransferSnapshot>> historyByClientId =
                loadHistory(matching.matched(), clients);

        List<DocumentTransferSnapshot> toSave = new ArrayList<>(matching.matched().size());
        List<ClientEvent> events = new ArrayList<>(matching.matched().size());
        for (SnapshotCreateCommand command : matching.matched()) {
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

        return new SnapshotImportReport(toSave.size(), matching.unmatchedKcrCodes(),
                result.getSkippedSheets(), result.getIssues());
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
            log.warn("Snapshot import file rejected: {}", e.toString());
            log.debug("Snapshot import file parsing failure", e);
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

    private Matching matchClients(List<SnapshotCreateCommand> commands, Map<String, Client> clients) {
        List<SnapshotCreateCommand> matched = new ArrayList<>();
        List<String> unmatchedKcrCodes = new ArrayList<>();
        for (SnapshotCreateCommand command : commands) {
            if (clients.containsKey(command.kcrCode())) {
                matched.add(command);
            } else {
                unmatchedKcrCodes.add(command.kcrCode());
            }
        }
        return new Matching(matched, unmatchedKcrCodes);
    }

    private Map<Long, List<DocumentTransferSnapshot>> loadHistory(List<SnapshotCreateCommand> commands,
                                                                   Map<String, Client> clients) {
        if (commands.isEmpty()) {
            return Map.of();
        }
        List<Long> clientIds = commands.stream()
                .map(command -> clients.get(command.kcrCode()).getId())
                .distinct()
                .toList();
        return snapshotRepository.findByClientIdInOrderByClientIdAscSnapshotDateAscIdAsc(clientIds)
                .stream()
                .collect(Collectors.groupingBy(snapshot -> snapshot.getClient().getId()));
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

    private record Matching(List<SnapshotCreateCommand> matched, List<String> unmatchedKcrCodes) {
    }
}
