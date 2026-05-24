"""Tests for the local-folder source (crawler/sources/localfolder.py)."""

from __future__ import annotations

import pytest

from crawler.config import settings
from crawler.sources.localfolder import LocalFolderSource, LocalFolderUnavailable


def test_unavailable_when_not_configured(monkeypatch) -> None:
    monkeypatch.setattr(settings, "local_source_dir", None)
    with pytest.raises(LocalFolderUnavailable):
        LocalFolderSource()


def test_unavailable_when_not_a_directory(monkeypatch, tmp_path) -> None:
    bogus = tmp_path / "nope"
    monkeypatch.setattr(settings, "local_source_dir", bogus)
    with pytest.raises(LocalFolderUnavailable):
        LocalFolderSource()


def test_discover_and_read(monkeypatch, tmp_path) -> None:
    (tmp_path / "notes.txt").write_text("Plain text study notes about sharing rules.", "utf-8")
    (tmp_path / "guide.md").write_text("# Admin guide\n\nProfiles vs permission sets.", "utf-8")
    (tmp_path / "page.html").write_text(
        "<html><body><article><div class='entry-content'><h1>OWD</h1><p>Org-wide defaults set the baseline.</p></div></article></body></html>",
        "utf-8",
    )
    (tmp_path / "ignore.bin").write_bytes(b"\x00\x01\x02")  # not a readable extension
    sub = tmp_path / "sub"
    sub.mkdir()
    (sub / "deep.md").write_text("Nested markdown file.", "utf-8")

    monkeypatch.setattr(settings, "local_source_dir", tmp_path)
    src = LocalFolderSource()
    items = src.discover(None, limit=10)
    names = {it.title for it in items}
    assert names == {"notes", "guide", "page", "deep"}  # the .bin file is skipped
    assert all(it.source_name == "localfolder" for it in items)
    assert src.fetch_via_http is False

    by_title = {it.title: it for it in items}
    title, text = src.fetch_content(by_title["notes"])
    assert "sharing rules" in text
    title, text = src.fetch_content(by_title["page"])
    assert "Org-wide defaults set the baseline" in text  # HTML stripped to text
    assert "<p>" not in text
    title, text = src.fetch_content(by_title["deep"])
    assert "Nested markdown file" in text


def test_discover_respects_limit(monkeypatch, tmp_path) -> None:
    for i in range(5):
        (tmp_path / f"f{i}.txt").write_text(f"file {i}", "utf-8")
    monkeypatch.setattr(settings, "local_source_dir", tmp_path)
    src = LocalFolderSource()
    assert len(src.discover(None, limit=3)) == 3


def test_image_files_are_discovered_and_returned_via_fetch_image(monkeypatch, tmp_path) -> None:
    # PNG signature header — enough bytes to look like image content for our test.
    png_bytes = b"\x89PNG\r\n\x1a\n" + b"fake-image-payload"
    (tmp_path / "screen1.png").write_bytes(png_bytes)
    (tmp_path / "photo.JPG").write_bytes(b"\xff\xd8\xff\xe0jpeg-fake")
    (tmp_path / "notes.md").write_text("just notes", "utf-8")
    monkeypatch.setattr(settings, "local_source_dir", tmp_path)
    src = LocalFolderSource()
    items = src.discover(None, limit=10)
    by_title = {it.title: it for it in items}
    # all three discovered
    assert {"screen1", "photo", "notes"} <= set(by_title)

    png_item = by_title["screen1"]
    assert png_item.meta.get("kind") == "image"
    assert png_item.meta.get("mime_type") == "image/png"
    title, text = src.fetch_content(png_item)
    assert title == "screen1" and text == ""  # images carry no text
    payload = src.fetch_image(png_item)
    assert payload is not None
    raw, mime = payload
    assert raw == png_bytes and mime == "image/png"

    jpg_item = by_title["photo"]
    assert jpg_item.meta.get("mime_type") == "image/jpeg"
    assert src.fetch_image(jpg_item)[1] == "image/jpeg"

    # text item: fetch_image returns None, fetch_content returns the text
    notes_item = by_title["notes"]
    assert notes_item.meta.get("kind") != "image"
    assert src.fetch_image(notes_item) is None
    assert src.fetch_content(notes_item) == ("notes", "just notes")
