@component @http
Feature: Account authentication at the HTTP boundary
  The real security filter chain protects accounts while the identity repository is mocked.

  Scenario: An anonymous browser cannot read an account
    When I request my account
    Then the response status is 401
    And the error code is "AUTHENTICATION_REQUIRED"

  Scenario: Login requires a CSRF token
    When I log in as "learner01" without a CSRF token
    Then the response status is 403
    And the error code is "CSRF_INVALID"

  Scenario Outline: Invalid credentials do not reveal whether an account exists
    When I log in as "<username>" with an invalid password
    Then the response status is 401
    And the error code is "INVALID_CREDENTIALS"
    Examples:
      | username       |
      | learner01      |
      | unknown-learner |

  Scenario: Login rotates the session and protects the account response
    Given I am logged in as "learner01"
    Then authentication rotated the browser session
    And the response is not cacheable
    And the response does not contain credentials

  Scenario: A different browser does not inherit the session
    Given I am logged in as "learner01"
    When I open a new anonymous browser
    And I request my account
    Then the response status is 401

  Scenario: Logout ends the authenticated session
    Given I am logged in as "learner01"
    When I log out
    Then the response status is 204
    When I request my account
    Then the response status is 401

  Scenario: Storage failure is retryable and does not masquerade as bad credentials
    When I log in as "storage-outage" with an invalid password
    Then the response status is 503
    And the error code is "ACCOUNT_STORAGE_UNAVAILABLE"
    And the response is not cacheable
    When I request my account
    Then the response status is 401
