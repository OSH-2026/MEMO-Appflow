import json
import tempfile
import unittest
from pathlib import Path

import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import android_ebpf_collect as collector


class AndroidEbpfCollectTest(unittest.TestCase):
    def test_parse_fixture_generates_structured_outputs(self):
        fixture_dir = ROOT / "fixtures"
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp)
            manifest = collector.parse_and_structure(
                fixture_dir / "sample_trace.tsv",
                out,
                actions_json=fixture_dir / "sample_actions.json",
                uid_map_json=fixture_dir / "sample_uid_map.json",
                config_path=ROOT / "configs" / "service_categories.json",
            )

            self.assertGreater(manifest["counts"]["normalized_events"], 0)
            self.assertGreater(manifest["counts"]["binder_service_segments"], 0)
            self.assertGreater(manifest["counts"]["cold_launch_file_profile"], 0)
            self.assertGreater(manifest["counts"]["memory_pressure_segments"], 0)
            self.assertGreater(manifest["counts"]["semantic_evidence_segments"], 0)
            self.assertGreater(manifest["counts"]["llm_context_windows"], 0)

            llm_path = Path(manifest["outputs"]["llm_context_windows"])
            contexts = [json.loads(line) for line in llm_path.read_text(encoding="utf-8").splitlines()]
            self.assertEqual(contexts[0]["schema_version"], "memo.llm_context.v1")
            self.assertIn("stage_1", contexts[0]["prediction_contract"])
            self.assertIn("stage_2", contexts[0]["prediction_contract"])

    def test_path_category_is_privacy_preserving(self):
        raw = collector.RawEvent(
            ts_ns=1,
            event_type="file",
            uid=10123,
            pid=1,
            tid=1,
            comm="camera",
            detail="/data/user/0/com.android.camera/databases/settings.db",
        )
        window = collector.Window(
            window_id=1,
            scenario_id="test",
            package_name="com.android.camera",
            app_alias="camera",
            action_type="open",
            resource_phase="cold_launch",
            start_ts_ns=0,
            end_ts_ns=2,
        )
        events = collector.normalize_events(
            [raw],
            [window],
            {10123: ["com.android.camera"]},
            collector.load_json(ROOT / "configs" / "service_categories.json"),
        )
        self.assertEqual(events[0]["path_category"], "database")
        self.assertEqual(events[0]["detail"], "")
        self.assertEqual(events[0]["privacy_level"], "P1")


if __name__ == "__main__":
    unittest.main()
