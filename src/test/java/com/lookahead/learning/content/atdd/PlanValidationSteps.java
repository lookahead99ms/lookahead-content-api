package com.lookahead.learning.content.atdd;

import com.lookahead.learning.content.validator.SnapshotValidator;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.When;
import io.cucumber.java.en.Then;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.assertThat;

public class PlanValidationSteps {
    private final JsonMapper mapper = new JsonMapper();
    private SnapshotValidator validator;
    private ObjectNode plan;
    private Set<String> grants;
    private boolean legacy;
    private IllegalArgumentException failure;
    private JsonNode provenance;

    @Given("a synthetic plan and its trusted catalog")
    public void fixture() throws Exception {
        try (var catalog = getClass().getResourceAsStream("/accounts/catalog.json");
             var payload = getClass().getResourceAsStream("/accounts/generated-plan.json")) {
            validator = new SnapshotValidator(mapper, mapper.readTree(catalog));
            plan = (ObjectNode) mapper.readTree(payload);
        }
        grants = Set.of("learn:core-java");
    }

    @Given("the plan has {string}")
    public void defect(String defect) {
        ObjectNode snapshot = (ObjectNode) plan.path("snapshot");
        ObjectNode assignment = (ObjectNode) snapshot.path("days").get(0).path("assignments").get(0);
        ObjectNode pins = (ObjectNode) plan.path("provenance");
        switch (defect) {
            case "no server topic grants" -> grants = Set.of();
            case "an external route" -> assignment.putArray("route").add("https://example.invalid/lesson");
            case "an unknown content ID" -> assignment.put("sourceContentId", "unknown-unpublished");
            case "contradictory weeks" -> ((ObjectNode) snapshot.path("weeks").get(0).path("days").get(0)).put("focus", "contradiction");
            case "a different catalog pin" -> pins.put("catalogVersion", "sha256:" + "0".repeat(64));
            case "an unknown ranking pin" -> pins.put("rankingVersion", "unknown-ranking/v0");
            default -> throw new IllegalArgumentException("Unknown test defect: " + defect);
        }
        if (defect.equals("an external route") || defect.equals("an unknown content ID")) {
            ((ObjectNode) snapshot.path("weeks").get(0)).set("days", snapshot.path("days").deepCopy());
        }
    }

    @Given("the plan is a legacy import with unknown pins")
    public void legacy() {
        legacy = true;
        ((ObjectNode) plan.path("provenance")).put("origin", "legacy-local-import")
                .putNull("catalogVersion").putNull("rankingVersion");
    }

    @When("I validate the plan")
    public void validate() {
        try {
            provenance = validator.validate(plan.path("snapshot"), plan.path("provenance"), legacy, grants).provenance();
        } catch (IllegalArgumentException error) {
            failure = error;
        }
    }

    @Then("the plan is accepted")
    public void accepted() { assertThat(failure).isNull(); assertThat(provenance).isNotNull(); }

    @Then("the plan is rejected")
    public void rejected() { assertThat(failure).isNotNull(); }

    @Then("historical catalog provenance remains unknown")
    public void unknown() {
        assertThat(provenance.path("catalogVersion").isNull()).isTrue();
        assertThat(provenance.path("historicalProvenance").asText()).isEqualTo("unknown");
    }
}
