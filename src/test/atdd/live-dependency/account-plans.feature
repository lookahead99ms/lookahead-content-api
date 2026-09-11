@live
Feature: Account plans persisted through the live API
  These scenarios require an explicitly configured isolated account API and PostgreSQL.
  They create synthetic plans and delete only the plans they created.

  Background:
    Given I am logged in as "learner01"
    And I have saved a synthetic plan

  Scenario: A saved plan survives a new login
    When I log in again as "learner01"
    And I read the saved plan
    Then the response status is 200
    And the saved snapshot is unchanged

  Scenario: Another account cannot read my plan
    When I log in again as "learner02"
    And I read the saved plan
    Then the response status is 404
    And the response does not disclose the plan revision

  Scenario: Another account cannot update my plan
    When I log in again as "learner02"
    And I save a note with the original revision
    Then the response status is 404

  Scenario: An attempt does not complete a session or canonical content
    When I record a needs-review attempt
    Then the response status is 200
    And the attempt is recorded separately from completion

  Scenario: Session completion does not imply canonical completion
    When I complete only the planned session
    Then the response status is 200
    And only the session is complete

  Scenario: Retrying a note save returns the same result
    When I save a note with the original revision
    Then the response status is 200
    When I repeat the exact activity request
    Then the response status is 200
    And the activity response is unchanged

  Scenario: Reusing a request key for different work is rejected
    When I save a note with the original revision
    Then the response status is 200
    When I reuse the activity key with a different note
    Then the response status is 409
    And the error code is "IDEMPOTENCY_KEY_REUSED"

  Scenario: A stale edit cannot overwrite a newer revision
    When I save a note with the original revision
    Then the response status is 200
    When I save a note with the original revision
    Then the response status is 409
    And the error code is "REVISION_CONFLICT"
