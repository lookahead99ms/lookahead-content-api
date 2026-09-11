@live
Feature: Prerequisite explanations never become authorization
  Background:
    Given I have saved a synthetic persona 9 plan

  Scenario: Known inaccessible prerequisites may be explained without granting progress
    When I include known inaccessible prerequisite metadata
    Then the response status is 201

  Scenario Outline: Metadata cannot introduce unauthorized assignments
    When I submit invalid restricted metadata "<kind>"
    Then the response status is 422
    Examples:
      | kind                 |
      | unknown prerequisite |
      | inaccessible item    |
      | future review        |
