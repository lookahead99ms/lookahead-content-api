#!/usr/bin/env python3
"""Compatibility command for Java/Cucumber verification; contains no duplicate assertions."""
from cucumber_probe import main
if __name__ == '__main__':
    raise SystemExit(main())
