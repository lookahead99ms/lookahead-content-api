@live
Feature: Persistence integrity beyond the basic account journey
  Background:
    Given I am logged in as "learner01"
    And I have saved a synthetic plan

  Scenario Outline: Tampered snapshots never become account plans
    When I submit a snapshot with invalid "<field>"
    Then the response status is 422
    Examples:
      | field     |
      | route     |
      | canonical |
      | weeks     |
      | pins      |

  Scenario: A stale account-comparison header cannot read the current account plan
    Given my expected account header names a different account
    When I read the saved plan
    Then the response status is 401
    And the error code is "ACCOUNT_CHANGED"

  Scenario: Concurrent identical retries commit one mutation
    When two independent sessions submit an identical note with one key
    Then both requests return the same saved revision

  Scenario: Concurrent edits from one revision produce one winner
    When two independent sessions submit different notes from one revision
    Then exactly one edit succeeds and the other reports a revision conflict

  Scenario: Recovery preserves immutable history and the fixed deadline
    When I create a recovery version
    Then the response status is 201
    And the recovery deadline is unchanged
    When I read the original version
    Then the response status is 200
    And the saved snapshot is unchanged

  Scenario: A deadline extension requires the explicit extension strategy
    When I submit an implicit deadline extension
    Then the response status is 422

  Scenario: Original import history is preserved without inventing historical pins
    When I import a legacy browser plan
    Then the response status is 201
    And legacy provenance and notes remain intact
    When I repeat the exact legacy import
    Then the response status is 201
    And the legacy import response is unchanged

  Scenario: A missing expected revision cannot overwrite activity
    When I submit activity without an expected revision
    Then the response status is 428
