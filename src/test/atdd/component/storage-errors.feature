@component
Feature: Storage failures have safe retryable responses
  Simulate dependency failures without starting PostgreSQL or containers.

  Scenario Outline: Database failures preserve the retry contract
    Given storage fails during "<stage>"
    When an account request reaches the error handler
    Then the storage response status is 503
    And the storage response code is "ACCOUNT_STORAGE_UNAVAILABLE"
    And the storage response is private and contains no driver details
    Examples:
      | stage                  |
      | connection acquisition |
      | query                  |
      | rollback               |
      | commit                 |

  Scenario: A programming error is not reported as a database outage
    Given storage fails during "unrelated programming error"
    When an account request reaches the error handler
    Then the storage response status is 500
    And the response does not claim a retryable storage outage
