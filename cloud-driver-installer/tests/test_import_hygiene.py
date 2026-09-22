"""The package must never shadow a standard library module, however it was started.

Running a file *inside* the package (``python src/cloud_driver_installer/__main__.py`` - what an
IDE run configuration does) puts the package directory itself on ``sys.path``, where a module of
ours outranks the standard library module of the same name for the whole process. That is how
``credentials.py``, then called ``secrets.py``, once made its own generators call themselves
(``module 'secrets' has no attribute 'token_hex'``). ``cloud_driver_installer/__init__`` drops that
directory; these tests hold both halves in place.
"""

from __future__ import annotations

import os
import secrets as stdlib_secrets
import subprocess
import sys
from pathlib import Path

import cloud_driver_installer
from cloud_driver_installer import credentials

#: The directory the package lives in - the one that must never stay on ``sys.path``.
PACKAGE_DIR = Path(cloud_driver_installer.__file__).parent

#: Its parent, the importable source root.
SOURCE_ROOT = PACKAGE_DIR.parent


def test_the_generators_use_the_standard_library() -> None:
    """The randomness comes from the real :mod:`secrets`, not from anything of ours."""
    assert credentials._secrets is stdlib_secrets
    assert len(credentials.generate_hex(24)) == 48
    assert len(credentials.generate_base64(32)) == 44


def test_importing_the_package_drops_its_own_directory_from_sys_path() -> None:
    """With the package directory first on the path, the standard library still wins."""
    script = (
        "import sys;"
        f" sys.path.insert(0, {str(PACKAGE_DIR)!r});"
        " import cloud_driver_installer;"
        " import secrets;"
        " from cloud_driver_installer.credentials import generate_hex;"
        f" print(sys.path.count({str(PACKAGE_DIR)!r}), secrets.token_hex(4) and len(generate_hex(24)))"
    )
    env = dict(os.environ, PYTHONPATH=str(SOURCE_ROOT))
    result = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True, env=env, timeout=120)
    assert result.returncode == 0, result.stderr
    assert result.stdout.split() == ["0", "48"]


def test_no_module_beside_main_shares_a_standard_library_name() -> None:
    """The modules an IDE-started ``__main__.py`` would expose must not collide with the stdlib.

    Only this directory is at risk: it is the one that lands on ``sys.path``. Subpackages are
    reached as ``cloud_driver_installer.<name>``, which can never shadow anything.
    """
    colliding = sorted(path.stem for path in PACKAGE_DIR.glob("*.py") if path.stem in sys.stdlib_module_names)
    assert colliding == [], f"rename these - the guard defuses them, but nothing should rely on it: {colliding}"
