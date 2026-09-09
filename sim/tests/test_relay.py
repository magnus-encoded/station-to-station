from datetime import datetime, timezone
import unittest

from station_to_station_sim.simulation import Config, Encounter, Fact, run_simulation


class RelayAcceptance(unittest.TestCase):
    def test_stranger_bridges_contacts_and_duplicate_stops_carry(self):
        fact = Fact("E", "Alice", 0, 100)
        trace = (Encounter(1, "Alice", "Carol"), Encounter(2, "Carol", "Bob"),
                 Encounter(3, "Alice", "Dave"), Encounter(4, "Dave", "Carol"),
                 Encounter(5, "Carol", "Erin"))
        config = Config(users=("Alice", "Bob", "Carol", "Dave", "Erin"), facts=(fact,),
                        encounters=trace, contacts=(("Alice", "Bob"),))
        result = run_simulation(config, "epidemic", 42, datetime(2026, 9, 10, tzinfo=timezone.utc))
        self.assertIn("E", result.received["Bob"])
        self.assertNotIn("E", result.received["Erin"])
        self.assertEqual(result.useful_deliveries, 1)
        self.assertEqual(result.duplicates, 1)

    def test_control_cannot_cross_stranger_and_runs_reproduce(self):
        config = Config(users=("Alice", "Carol", "Bob"), facts=(Fact("E", "Alice", 0, 100),),
                        encounters=(Encounter(1, "Alice", "Carol"), Encounter(2, "Carol", "Bob")),
                        contacts=(("Alice", "Bob"),))
        start = datetime(2026, 9, 10, tzinfo=timezone.utc)
        self.assertNotIn("E", run_simulation(config, "contact", 42, start).received["Bob"])
        self.assertEqual(run_simulation(config, "epidemic", 42, start),
                         run_simulation(config, "epidemic", 42, start))

    def test_outbox_expires_without_erasing_received_fact(self):
        config = Config(users=("Alice", "Carol", "Bob"), facts=(Fact("E", "Alice", 0, 100),),
                        encounters=(Encounter(1, "Alice", "Carol"), Encounter(20, "Carol", "Bob")),
                        carry_seconds=10)
        result = run_simulation(config, "epidemic", 1, datetime(2026, 9, 10, tzinfo=timezone.utc))
        self.assertIn("E", result.received["Carol"])
        self.assertNotIn("E", result.received["Bob"])
