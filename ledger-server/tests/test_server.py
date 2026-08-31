import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from server import LedgerStore


def event(event_id="event-0001", tx_id="tx-1", amount=1600, deleted=False):
    payload = {"id": tx_id, "deleted": True, "updated_at_ms": 2} if deleted else {
        "id": tx_id, "type": "EXPENSE", "amount_cents": amount, "currency": "CNY",
        "category": "餐饮", "account": "现金", "target_account": None,
        "occurred_at_ms": 1, "note": None, "updated_at_ms": 2,
    }
    return {"event_id": event_id, "transaction_id": tx_id, "payload": payload}


class LedgerStoreTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = LedgerStore(str(Path(self.temp.name) / "ledger.sqlite3"))

    def tearDown(self):
        self.temp.cleanup()

    def test_duplicate_event_is_idempotent(self):
        first = self.store.sync(0, [event()])
        second = self.store.sync(first["cursor"], [event()])
        self.assertEqual(1, len(first["changes"]))
        self.assertEqual([], second["changes"])
        self.assertEqual(first["cursor"], second["cursor"])

    def test_update_and_delete_are_ordered_changes(self):
        first = self.store.sync(0, [event()])
        updated = self.store.sync(first["cursor"], [event("event-0002", amount=2456)])
        deleted = self.store.sync(updated["cursor"], [event("event-0003", deleted=True)])
        self.assertEqual(2456, updated["changes"][0]["payload"]["amount_cents"])
        self.assertTrue(deleted["changes"][0]["payload"]["deleted"])

    def test_second_device_can_replay_from_zero(self):
        self.store.sync(0, [event(), event("event-0002", amount=2456)])
        replay = self.store.sync(0, [])
        self.assertEqual(2, len(replay["changes"]))
        self.assertEqual([1600, 2456], [row["payload"]["amount_cents"] for row in replay["changes"]])

    def test_daily_online_backup_is_created(self):
        self.store.sync(0, [event()])
        backups = list((Path(self.temp.name) / "backups").glob("ledger-*.sqlite3"))
        self.assertEqual(1, len(backups))
        self.assertGreater(backups[0].stat().st_size, 0)


if __name__ == "__main__":
    unittest.main()
