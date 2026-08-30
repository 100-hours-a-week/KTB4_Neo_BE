package com.ktb.community.benchmark.draftatomicity;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Profile("perf")
@RequestMapping("/perf/redis/drafts")
@RequiredArgsConstructor
public class DraftAtomicBenchmarkController {

    private final DraftAtomicBenchmarkService service;

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        service.reset();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/initialize")
    public ResponseEntity<Void> initialize(
            @Valid @RequestBody DraftAtomicInitializeRequest request
    ) {
        service.initialize(request);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{strategy}/{draftId}/autosave")
    public DraftAtomicResult autosave(
            @PathVariable DraftAtomicStrategy strategy,
            @PathVariable Long draftId,
            @Valid @RequestBody DraftAtomicBenchmarkRequest request
    ) {
        return service.autosave(strategy, draftId, request);
    }

    @PostMapping("/{strategy}/{draftId}/remove-dirty")
    public Map<String, Object> removeDirty(
            @PathVariable DraftAtomicStrategy strategy,
            @PathVariable Long draftId,
            @Valid @RequestBody DraftAtomicRemoveDirtyRequest request
    ) {
        return service.removeDirty(
                strategy,
                draftId,
                request.rdbContentVersion()
        );
    }

    @PostMapping("/{strategy}/{draftId}/remove-dirty-cycle")
    public Map<String, Object> removeDirtyCycle(
            @PathVariable DraftAtomicStrategy strategy,
            @PathVariable Long draftId,
            @Valid @RequestBody DraftAtomicRemoveDirtyRequest request
    ) {
        return service.removeDirtyCycle(
                strategy,
                draftId,
                request.rdbContentVersion()
        );
    }

    @GetMapping("/{draftId}/state")
    public DraftAtomicState state(@PathVariable Long draftId) {
        return service.state(draftId);
    }

    @PostMapping("/{draftId}/orphan-dirty")
    public ResponseEntity<Void> createOrphanDirty(
            @PathVariable Long draftId
    ) {
        service.createOrphanDirty(draftId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return service.metrics();
    }
}
