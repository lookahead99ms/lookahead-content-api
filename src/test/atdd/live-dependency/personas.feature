@live
Feature: Distinct learner configurations persist through new sessions
  Scenario Outline: Persona progress is account-owned and exact after login
    Given I have saved a synthetic persona <number> plan
    When I record an independent persona note and attempt
    And I log in again as "<username>"
    And I read the saved plan
    Then the response status is 200
    And the complete saved state matches the last activity
    And the saved snapshot is unchanged
    Examples:
      | number | username  |
      | 1      | learner01 |
      | 2      | learner02 |
      | 3      | learner03 |
      | 4      | learner04 |
      | 5      | learner05 |
      | 6      | learner06 |
      | 7      | learner07 |
      | 8      | learner08 |
      | 9      | learner09 |
      | 10     | learner10 |
