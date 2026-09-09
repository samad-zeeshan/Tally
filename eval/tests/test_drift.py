"""
Every number in the README and in the demo's data comes from a file under eval/. This fails if one drifted.
"""

import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class Drift(unittest.TestCase):
    def test_readme_and_site_data_match_the_results_files(self):
        proc = subprocess.run([sys.executable, str(ROOT / "eval" / "report.py"), "--check"], capture_output=True, text=True)
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)


if __name__ == "__main__":
    unittest.main()
