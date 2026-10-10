"""
Check the words a visitor reads, on the demo page and in the README, against the plain-language rules.

Reads the text of site/index.html, the quoted strings in site/app.js, and the README prose outside code and tables.
"""
import re
import unittest
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "site"

# Names from the results files and the cluster setup. They mean nothing to a visitor, so they may
# not come back as labels or captions. HTTP header names in the raw request log are left alone on purpose.
# The README is for engineers too, so it may use these names as long as it explains them.
INTERNAL = [
    "model checker", "p99", "precision", "recall", "funds every opening", "states explored",
    "Postgres", "pod", "Kubernetes", "cluster", "harness", "injected",
    "reconciliation", "posting", "partition", "violation",
]
FILLER = [
    "comprehensive", "robust", "seamless", "cutting-edge", "state-of-the-art", "powerful", "leverage",
    "utilize", "ensure", "delve", "showcase", "crucial", "various", "numerous", "simply", "just",
    "really", "basically", "actually",
]
MAX_WORDS = 25

# A form of "be", then at most one word, then a past participle. It is a rough net for passive
# voice, so it also catches "is a balanced entry". Such sentences go in PASSIVE_OK with a reason.
PARTICIPLES = "made done found given held kept left run seen sent shown taken written built caught"
PASSIVE = re.compile(
    r"\b(?:is|are|was|were|be|been|being)\s+(?:\w+\s+)?(?:\w+ed|" + PARTICIPLES.replace(" ", "|") + r")\b",
    re.I,
)
PASSIVE_OK = {
    # Each entry is an exact sentence, with a one-line reason above it.
}


class _Text(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts, self.skip = [], False

    def handle_starttag(self, tag, attrs):
        self.skip = tag in ("script", "style")
        for name, value in attrs:
            if name in ("content", "aria-label", "alt", "title") and value:
                self.parts.append(value)

    def handle_endtag(self, tag):
        self.skip = False

    def handle_data(self, data):
        if not self.skip and data.strip():
            self.parts.append(data)


def page_strings():
    parser = _Text()
    parser.feed((SITE / "index.html").read_text(encoding="utf-8"))
    js = (SITE / "app.js").read_text(encoding="utf-8")
    # Comments may name the internals. Only double-quoted literals reach the page.
    js = re.sub(r"/\*.*?\*/|//[^\n]*", "", js, flags=re.S)
    quoted = [m.replace('\\"', '"') for m in re.findall(r'"((?:[^"\\\n]|\\.)*)"', js)]
    return parser.parts + quoted


def readme_prose():
    text = (ROOT / "README.md").read_text(encoding="utf-8")
    text = re.sub(r"```.*?```", "", text, flags=re.S)
    # Tables hold exact figures and short headers, and the markers are HTML comments.
    lines = [ln for ln in text.splitlines() if not ln.startswith(("|", "<!--"))]
    # Inline code is a name or a command, not prose. Keep a stand-in so word counts stay honest.
    # Image markup is a path, and a link counts only for its visible text.
    out = []
    for ln in lines:
        ln = re.sub(r"`[^`]*`", "CODE", ln)
        ln = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", ln)
        out.append(re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", ln))
    return [ln for ln in out if ln.strip()]


def sentences(texts):
    for text in texts:
        yield from (s for s in re.split(r"(?<=[.?!])\s+", " ".join(text.split())) if s)


class SiteCopy(unittest.TestCase):
    def test_no_internal_names_on_the_page(self):
        for text in page_strings():
            for word in INTERNAL:
                with self.subTest(word=word):
                    self.assertIsNone(re.search(r"\b" + re.escape(word), text, re.I), f"{word!r} in {text!r}")

    def test_no_filler_words(self):
        for text in page_strings() + readme_prose():
            for word in FILLER:
                self.assertIsNone(re.search(r"\b" + re.escape(word) + r"\b", text, re.I), f"{word!r} in {text!r}")

    def test_no_dashes_or_semicolons(self):
        for text in page_strings() + readme_prose():
            self.assertNotRegex(text, "[—;]", text)

    def test_sentences_stay_short(self):
        for sentence in sentences(page_strings() + readme_prose()):
            self.assertLessEqual(len(sentence.split()), MAX_WORDS, sentence)

    def test_active_voice(self):
        for sentence in sentences(page_strings() + readme_prose()):
            if sentence not in PASSIVE_OK:
                self.assertIsNone(PASSIVE.search(sentence), sentence)


if __name__ == "__main__":
    unittest.main()
