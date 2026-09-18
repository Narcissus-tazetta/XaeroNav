import tempfile
import unittest
from pathlib import Path

from release_notes import validate_release_notes


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        (self.root / "changelogs").mkdir()

    def tearDown(self):
        self.directory.cleanup()

    def test_accepts_version_specific_notes(self):
        notes = self.root / "changelogs" / "0.2.1.md"
        notes.write_text("## Fixed\n\n- A route issue.\n", encoding="utf-8")
        self.assertEqual(notes, validate_release_notes(self.root, "0.2.1"))

    def test_rejects_missing_or_empty_notes(self):
        with self.assertRaisesRegex(ValueError, "missing"):
            validate_release_notes(self.root, "0.2.1")
        (self.root / "changelogs" / "0.2.1.md").write_text("  \n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "empty"):
            validate_release_notes(self.root, "0.2.1")

    def test_rejects_versions_that_could_escape_the_notes_directory(self):
        for version in ("../0.2.1", "v0.2.1", "0.2.1-beta", "0.2"):
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, "Invalid"):
                validate_release_notes(self.root, version)


if __name__ == "__main__":
    unittest.main()
