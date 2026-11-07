package ru.quard0r.kcrbackend.imports;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.quard0r.kcrbackend.exception.DomainException;
import ru.quard0r.kcrbackend.exception.ErrorCode;
import ru.quard0r.kcrbackend.imports.dto.ClientImportPreview;
import ru.quard0r.kcrbackend.imports.dto.ClientImportReport;
import ru.quard0r.kcrbackend.imports.dto.ClientImportRow;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.clients.repository.ClientRepository;
import ru.quard0r.kcrbackend.imports.dto.ClientUpsertCommand;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Client import over the Excel adapter (ARCHITECTURE.md §11.8 item 3): upsert by the unique
 * KCR code, clients absent from the file are never touched. An update patches only the fields
 * present in the file — real exports omit the INN/name columns, which are NOT NULL in the
 * schema and must not be wiped. A new client without INN or organization name cannot be
 * created and is reported as a row issue.
 */
@Service
@RequiredArgsConstructor
public class ClientUpsertService {

    private final ClientRepository clientRepository;
    private final ClientExcelParser parser = new ClientExcelParser();

    public ClientImportPreview preview(MultipartFile file) {
        ParseResult<ClientUpsertCommand> result = parse(file);
        Map<String, Client> existing = loadExisting(result.getCommands());

        List<ClientImportRow> toCreate = new ArrayList<>();
        List<ClientImportRow> toUpdate = new ArrayList<>();
        List<ParseIssue> issues = new ArrayList<>(result.getIssues());
        for (ClientUpsertCommand command : result.getCommands()) {
            boolean isNew = !existing.containsKey(command.kcrCode());
            if (isNew && !hasRequiredCard(command)) {
                issues.add(missingCardIssue(command));
                continue;
            }
            ClientImportRow row = new ClientImportRow(command.kcrCode(), command.organizationName());
            if (isNew) {
                toCreate.add(row);
            } else {
                toUpdate.add(row);
            }
        }
        return new ClientImportPreview(toCreate, toUpdate, issues);
    }

    @Transactional
    public ClientImportReport apply(MultipartFile file) {
        ParseResult<ClientUpsertCommand> result = parse(file);
        Map<String, Client> existing = loadExisting(result.getCommands());

        int created = 0;
        int updated = 0;
        List<ParseIssue> issues = new ArrayList<>(result.getIssues());
        for (ClientUpsertCommand command : result.getCommands()) {
            Client client = existing.get(command.kcrCode());
            if (client == null) {
                if (!hasRequiredCard(command)) {
                    issues.add(missingCardIssue(command));
                    continue;
                }
                clientRepository.save(toNewClient(command));
                created++;
            } else {
                patch(client, command);
                clientRepository.save(client);
                updated++;
            }
        }
        return new ClientImportReport(created, updated, issues);
    }

    private ParseResult<ClientUpsertCommand> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DomainException(ErrorCode.IMPORT_FILE_REQUIRED);
        }
        try {
            return parser.parse(file.getInputStream());
        } catch (IOException | RuntimeException e) {
            throw new DomainException(ErrorCode.IMPORT_FILE_UNREADABLE);
        }
    }

    private Map<String, Client> loadExisting(List<ClientUpsertCommand> commands) {
        if (commands.isEmpty()) {
            return Map.of();
        }
        return clientRepository.findByKcrCodeIn(commands.stream().map(ClientUpsertCommand::kcrCode).toList())
                .stream()
                .collect(Collectors.toMap(Client::getKcrCode, Function.identity()));
    }

    private boolean hasRequiredCard(ClientUpsertCommand command) {
        return !isBlank(command.inn()) && !isBlank(command.organizationName());
    }

    private ParseIssue missingCardIssue(ClientUpsertCommand command) {
        List<String> missing = new ArrayList<>();
        if (isBlank(command.inn())) {
            missing.add("INN");
        }
        if (isBlank(command.organizationName())) {
            missing.add("organization name");
        }
        return new ParseIssue(null, null, null, "New client %s cannot be created: missing %s"
                .formatted(command.kcrCode(), String.join(", ", missing)));
    }

    private Client toNewClient(ClientUpsertCommand command) {
        return Client.builder()
                .kcrCode(command.kcrCode())
                .inn(command.inn())
                .kpp(command.kpp())
                .organizationName(command.organizationName())
                .internalId(command.internalId())
                .clientType(command.clientType())
                .kcrActive(command.kcrActive())
                .kcrBlocked(command.kcrBlocked())
                .tariff(command.tariff())
                .ekbTravelTime(command.ekbTravelTime())
                .manager(command.manager())
                .flags(command.flags())
                .contactPerson(command.contactPerson())
                .contactPhone(command.contactPhone())
                .contactEmail(command.contactEmail())
                .build();
    }

    private void patch(Client client, ClientUpsertCommand command) {
        if (command.inn() != null) {
            client.setInn(command.inn());
        }
        if (command.kpp() != null) {
            client.setKpp(command.kpp());
        }
        if (command.organizationName() != null) {
            client.setOrganizationName(command.organizationName());
        }
        if (command.internalId() != null) {
            client.setInternalId(command.internalId());
        }
        if (command.clientType() != null) {
            client.setClientType(command.clientType());
        }
        if (command.kcrActive() != null) {
            client.setKcrActive(command.kcrActive());
        }
        if (command.kcrBlocked() != null) {
            client.setKcrBlocked(command.kcrBlocked());
        }
        if (command.tariff() != null) {
            client.setTariff(command.tariff());
        }
        if (command.ekbTravelTime() != null) {
            client.setEkbTravelTime(command.ekbTravelTime());
        }
        if (command.manager() != null) {
            client.setManager(command.manager());
        }
        if (command.flags() != null) {
            client.setFlags(command.flags());
        }
        if (command.contactPerson() != null) {
            client.setContactPerson(command.contactPerson());
        }
        if (command.contactPhone() != null) {
            client.setContactPhone(command.contactPhone());
        }
        if (command.contactEmail() != null) {
            client.setContactEmail(command.contactEmail());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
