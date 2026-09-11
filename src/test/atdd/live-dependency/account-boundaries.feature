@live
Feature: Account ownership, bounded requests and complete persistence
  Background:
    Given I am logged in as "learner01"
    And I have saved a synthetic plan

  Scenario Outline: Other accounts cannot see history, revise or delete a plan
    When I log in again as "learner02"
    And I request the other account boundary "<operation>"
    Then the response status is 404
    And the response does not disclose the plan revision
    Examples:
      | operation |
      | history   |
      | version   |
      | delete    |

  Scenario: Account lists contain only owned plans
    When I log in again as "learner02"
    And I request the other account boundary "list"
    Then the response status is 200
    And the plan is absent from the account list

  Scenario Outline: Stale shared-tab identity cannot read or mutate
    When I submit a guarded "<operation>" request
    Then the response status is 401
    And the error code is "ACCOUNT_CHANGED"
    Examples:
      | operation |
      | list      |
      | create    |
      | activity  |
      | import    |

  Scenario Outline: Invalid HTTP bodies do not become plans
    When I submit an invalid plan request "<kind>"
    Then the response status is <status>
    Examples:
      | kind           | status |
      | malformed JSON | 400    |
      | deep JSON      | 400    |
      | missing key    | 400    |
      | forged owner   | 422    |

  Scenario Outline: Declared oversized bodies are rejected before upload
    When I exceed the declared body limit for "<kind>"
    Examples:
      | kind     |
      | plan     |
      | activity |

  Scenario: Explicit deadline extension preserves history
    When I explicitly extend the deadline by one day
    Then the response status is 201
    And the new deadline is one day later and original history is intact

  Scenario: Canonical completion reversal preserves session completion
    When I complete the original learning on study day 2
    And I reverse only canonical completion
    Then the response status is 200
    And only the session is complete

  Scenario: A stale deletion cannot erase a new revision
    When I save a note with the original revision
    And I delete with a stale revision
    Then the response status is 409
    And the error code is "REVISION_CONFLICT"

  Scenario: Delete replay is idempotent and hides deleted plans
    When I delete the current plan twice with the same key
    Then the response status is 204
    When I read the saved plan
    Then the response status is 404

  Scenario: Deleted imports cannot be resurrected by replay
    When I import a legacy browser plan
    Then the response status is 201
    When I delete the imported plan and retry its original import
    Then the response status is 410

  Scenario: Login rotates both session and CSRF credentials
    When I verify login rotation and stale CSRF rejection

  Scenario: Forged snapshot access cannot grant restricted content
    When I submit ungranted content as the restricted account
    Then the response status is 422

  Scenario: Equal schedules in different accounts have independent plan IDs and notes
    When another account saves the identical schedule
