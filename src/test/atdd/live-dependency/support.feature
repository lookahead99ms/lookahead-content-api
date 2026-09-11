@live @mail
Feature: Local support delivery and durable receipt replay
  These scenarios run only against the isolated, non-relaying SMTP capture.
  The stable synthetic request key also verifies replay after an API restart.

  Background:
    Given I am logged in as "learner10"

  Scenario: An image is accepted once and replay has a durable receipt
    When I submit a synthetic support message with a PNG image
    Then the response status is 202
    And the response is not cacheable
    When I repeat the identical support submission
    Then the response status is 202
    And the support receipt is accepted and unchanged
    When I change the support message with the same request key
    Then the response status is 409
    And the error code is "FEEDBACK_KEY_REUSED"

  Scenario: Renaming bytes to PNG does not make an accepted attachment
    When I submit a support attachment that is not an image
    Then the response status is 422
    And the error code is "FEEDBACK_INVALID"
