@component
Feature: Validate plans against trusted metadata
  Client input cannot grant access or change canonical destinations.

  Scenario: Accept a generated plan with valid catalog and ranking pins
    Given a synthetic plan and its trusted catalog
    When I validate the plan
    Then the plan is accepted

  Scenario Outline: Reject untrusted or contradictory plan data
    Given a synthetic plan and its trusted catalog
    And the plan has "<defect>"
    When I validate the plan
    Then the plan is rejected
    Examples:
      | defect                  |
      | no server topic grants  |
      | an external route       |
      | an unknown content ID   |
      | contradictory weeks     |
      | a different catalog pin |
      | an unknown ranking pin   |

  Scenario: Legacy imports preserve unknown provenance
    Given a synthetic plan and its trusted catalog
    And the plan is a legacy import with unknown pins
    When I validate the plan
    Then the plan is accepted
    And historical catalog provenance remains unknown
