#!/usr/bin/env python3
"""Fetch pinned desktop helpers for a local Linux development build."""
from pathlib import Path
from extractor_bundle import install_extractor

install_extractor("linux", Path(__file__).resolve().parents[1] / ".deps/tools")
