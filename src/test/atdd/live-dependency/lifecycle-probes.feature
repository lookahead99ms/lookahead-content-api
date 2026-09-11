@checkpoint
Feature: Coordinated lifecycle preserves account-owned state
  Infrastructure controls services between these explicitly selected phases.
  The probe never starts, stops or changes any container.

  @prepare
  Scenario: Prepare one owned persistence checkpoint
    When the "prepare" account lifecycle probe runs

  @resume
  Scenario: A fresh login resumes exact persisted state and replays a recovered write
    When the "resume" account lifecycle probe runs

  @outage
  Scenario: Database outage returns a stable error without losing pending work
    When the "outage" account lifecycle probe runs

  @cleanup
  Scenario: Remove only the checkpoint's owned synthetic plan
    When the "cleanup" account lifecycle probe runs

  @restore
  Scenario: Restored accounts expose the exact database inventory without application writes
    When the "restore" account lifecycle probe runs

  @network-outage
  Scenario: An unresponsive database has a bounded retryable login and write failure
    When the "network-outage" account lifecycle probe runs
