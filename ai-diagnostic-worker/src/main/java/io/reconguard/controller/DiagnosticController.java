package io.reconguard.controller;

import io.reconguard.ai.domain.KnownIssue;
import io.reconguard.health.service.DiagnosticService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/diagnostics")
public class DiagnosticController {

    private final DiagnosticService diagnosticService;

    public DiagnosticController(DiagnosticService diagnosticService) {
        this.diagnosticService = diagnosticService;
    }

    @PostMapping("/analyze")
    public List<KnownIssue> analyzeError(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestBody String errorDescription,
            @RequestParam(defaultValue = "3") int limit) {
        return diagnosticService.findDiagnoses(tenantId, errorDescription, limit);
    }
}