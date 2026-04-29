import json
import tempfile
import unittest
from pathlib import Path

import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import maple_context_from_ebpf as maple_adapter


class MapleContextFromEbpfTest(unittest.TestCase):
    def test_traceprint_events_generate_maple_context_with_system_evidence(self):
        events = [
            {
                "schema_version": "memo.ebpf.traceprint.v1",
                "event_type": "MEMO_OPENAT",
                "comm": "netd",
                "evidence_category": "native_library",
            },
            {
                "schema_version": "memo.ebpf.traceprint.v1",
                "event_type": "MEMO_BINDER",
                "trace_task": "cameraserver",
                "pid": 100,
                "to_proc": 200,
            },
            {
                "schema_version": "memo.ebpf.traceprint.v1",
                "event_type": "MEMO_RECLAIM_BEGIN",
                "comm": "kswapd0",
            },
        ]

        scenario = maple_adapter.build_maple_scenario(
            events,
            scenario_id="test_window",
            description="unit test",
        )
        context = scenario["context"]

        self.assertEqual(scenario["source"], "android_ebpf")
        self.assertIn("Native Runtime Loading", context["historical_app_categories"])
        self.assertIn("Android Service IPC", context["historical_app_categories"])
        self.assertTrue(context["system_evidence"])
        self.assertIn("eBPF", context["system_evidence"][0])
        self.assertIn("direct reclaim", context["memory_pressure"])
        self.assertIn("eBPF evidence", context["scheduler_goal"])

    def test_cli_writes_maple_scenarios_json(self):
        with tempfile.TemporaryDirectory() as temp:
            temp_path = Path(temp)
            events_path = temp_path / "events.jsonl"
            out_path = temp_path / "scenarios.json"
            events_path.write_text(
                json.dumps(
                    {
                        "event_type": "MEMO_BINDER",
                        "trace_task": "servicemanager",
                    }
                )
                + "\n",
                encoding="utf-8",
            )

            self.assertEqual(
                maple_adapter.main(["--events", str(events_path), "--out", str(out_path)]),
                0,
            )
            payload = json.loads(out_path.read_text(encoding="utf-8"))
            self.assertEqual(payload["schema_version"], "memo.maple_scenarios.v1")
            self.assertEqual(payload["scenarios"][0]["context"]["historical_app_categories"][0], "Android Service IPC")


if __name__ == "__main__":
    unittest.main()
