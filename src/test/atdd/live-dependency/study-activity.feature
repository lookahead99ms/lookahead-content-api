@live
Feature: Actual study days, consumed estimates and independent daily recall
  Background:
    Given I am logged in as "learner01"
    And I have saved a synthetic plan

  Scenario: Overdue original activity remains recorded after login and exact retry
    When I complete the original learning on study day 2
    Then the response status is 200
    And the study log contains 1 entries and 20 minutes on day 2
    When I repeat the exact activity request
    Then the response status is 200
    And the activity response is unchanged
    When I log in again as "learner01"
    And I read the saved plan
    Then the study log contains 1 entries and 20 minutes on day 2
    And the saved snapshot is unchanged

  Scenario: Daily recall has a separate identity and its own consumed budget
    When I complete the original learning on study day 2
    Then the response status is 200
    When I record a daily recall on study day 3
    Then the response status is 200
    And the study log contains 2 entries and 10 minutes on day 3
    And the original and daily recall have separate completion identities

  Scenario: Recall cannot be recorded before the original learning
    When I record a daily recall on study day 2
    Then the response status is 422

  Scenario: Daily recall cannot move back before actual completion
    When I complete the original learning on study day 2
    Then the response status is 200
    When I record a daily recall on study day 1
    Then the response status is 422
