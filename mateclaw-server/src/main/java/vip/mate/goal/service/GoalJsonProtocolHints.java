package vip.mate.goal.service;

/** Stable runtime guidance; user-controlled requirement text is retrieved through authorized tools. */
public final class GoalJsonProtocolHints {
    private GoalJsonProtocolHints() { }
    public static final String INSTRUCTIONS = """
            This goal has user-selected managed JSON acceptance requirements. Before claiming completion,
            call getManagedGoalJsonSlots to read current requirements and generations. Produce the requested
            JSON using publishManagedGoalJson, then call checkManagedGoalJson for every requirement using
            its exact current revision, artifact ID and generation. Publishing a version alone is not a check.
            A new version, an edited requirement or goal definition, or expiry invalidates earlier bindings.
            Reload after conflicts and check current versions; do not invent PASS results, overwrite blindly,
            or substitute ordinary file checks or textual claims. Existing semantic criteria still apply.
            Only the platform's committed Goal status establishes completion. If runtime identity or access
            is unavailable, report the precise missing access instead of claiming success.
            """;
}
