package ru.quard0r.kcrbackend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.quard0r.kcrbackend.imports.ClientUpsertService;
import ru.quard0r.kcrbackend.imports.SnapshotImportService;
import ru.quard0r.kcrbackend.imports.dto.ClientImportPreview;
import ru.quard0r.kcrbackend.imports.dto.ClientImportReport;
import ru.quard0r.kcrbackend.imports.dto.SnapshotImportPreview;
import ru.quard0r.kcrbackend.imports.dto.SnapshotImportReport;

import java.time.LocalDate;

/**
 * Excel import (ARCHITECTURE.md §11.4): preview validates the file against the current state
 * without writing, the apply endpoints write in one transaction and return a report with
 * row-level issues.
 */
@RestController
@RequestMapping("/api/v1/import")
@RequiredArgsConstructor
public class ImportController {

    private final ClientUpsertService clientUpsertService;
    private final SnapshotImportService snapshotImportService;

    @PostMapping("/clients/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClientImportPreview> previewClients(@RequestParam("file") MultipartFile file)
    {
        return ResponseEntity.ok(clientUpsertService.preview(file));
    }

    @PostMapping("/clients")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClientImportReport> importClients(@RequestParam("file") MultipartFile file)
    {
        return ResponseEntity.ok(clientUpsertService.apply(file));
    }

    @PostMapping("/snapshots/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SnapshotImportPreview> previewSnapshots(
            @RequestParam("file") MultipartFile file,
            @RequestParam("snapshotDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate snapshotDate,
            @RequestParam("reportingPeriod") String reportingPeriod)
    {
        return ResponseEntity.ok(snapshotImportService.preview(file, snapshotDate, reportingPeriod));
    }

    @PostMapping("/snapshots")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SnapshotImportReport> importSnapshots(
            @RequestParam("file") MultipartFile file,
            @RequestParam("snapshotDate") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate snapshotDate,
            @RequestParam("reportingPeriod") String reportingPeriod)
    {
        return ResponseEntity.ok(snapshotImportService.apply(file, snapshotDate, reportingPeriod));
    }
}
