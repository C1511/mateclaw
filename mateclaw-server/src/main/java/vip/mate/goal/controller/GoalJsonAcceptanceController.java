package vip.mate.goal.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.mate.common.result.R;
import vip.mate.goal.service.GoalJsonAcceptanceService;
import vip.mate.goal.service.ManagedGoalJsonService;

/** Authenticated user surface, intentionally not a model tool. */
@RestController
@RequestMapping("/api/v1/goals/{goalId}/json-acceptance")
@RequiredArgsConstructor
public class GoalJsonAcceptanceController {
    private final GoalJsonAcceptanceService acceptance;
    private final ManagedGoalJsonService artifacts;
    private final vip.mate.goal.service.GoalJsonBindingService bindings;

    @GetMapping
    public R<GoalJsonAcceptanceService.View> get(@PathVariable Long goalId, Authentication auth) {
        return R.ok(acceptance.get(goalId, username(auth)));
    }

    @PutMapping("/requirements/{criterionKey}")
    public R<GoalJsonAcceptanceService.Requirement> configure(@PathVariable Long goalId, @PathVariable String criterionKey,
            @RequestBody GoalJsonAcceptanceService.ConfigureRequest request, Authentication auth) {
        return R.ok(acceptance.configure(goalId, criterionKey, request, username(auth)));
    }

    @GetMapping("/artifacts")
    public R<java.util.List<ManagedGoalJsonService.Slot>> artifacts(@PathVariable Long goalId, Authentication auth) {
        return R.ok(artifacts.list(goalId, username(auth)));
    }

    @PostMapping("/artifacts/{slot}")
    public R<ManagedGoalJsonService.Artifact> publish(@PathVariable Long goalId, @PathVariable String slot,
            @RequestBody ManagedGoalJsonService.PublishRequest request, Authentication auth) {
        return R.ok(artifacts.publish(goalId, slot, request, username(auth)));
    }

    @GetMapping("/artifacts/versions/{artifactId}")
    public R<ManagedGoalJsonService.Content> version(@PathVariable Long goalId, @PathVariable String artifactId, Authentication auth) {
        return R.ok(artifacts.read(goalId, artifactId, username(auth)));
    }

    @GetMapping("/checks")
    public R<java.util.List<vip.mate.goal.service.GoalJsonBindingService.State>> checks(@PathVariable Long goalId, Authentication auth) {
        return R.ok(bindings.state(goalId, username(auth)));
    }

    @PostMapping("/checks/{criterionKey}")
    public R<vip.mate.goal.service.GoalJsonBindingService.Check> check(@PathVariable Long goalId, @PathVariable String criterionKey,
            @RequestBody vip.mate.goal.service.GoalJsonBindingService.CheckRequest request, Authentication auth) {
        return R.ok(bindings.check(goalId, criterionKey, request, username(auth)));
    }

    private static String username(Authentication auth) {
        return auth != null && auth.isAuthenticated() ? auth.getName() : null;
    }
}
