"""GUI installer for the cloud-driver system.

Everything that talks to the server goes through :mod:`cloud_driver_installer.remote`, everything
that talks to AWS through :mod:`cloud_driver_installer.aws`, and every unit of work is a
:class:`cloud_driver_installer.engine.Step` (check -> apply -> verify, idempotent). The tkinter
window under :mod:`cloud_driver_installer.gui` only drives those; it holds no logic of its own, so
the engine can be tested (and later run headless) without a display.
"""

import os as _os
import sys as _sys

# Running a file *inside* this package (``python src/cloud_driver_installer/__main__.py``, which is
# what an IDE run configuration does) puts this directory itself on sys.path, where every module in
# it shadows the standard library module of the same name for the whole process - ``profile``, and
# once upon a time ``secrets``, whose generators then called themselves instead of the real ones.
# Nothing should ever import our modules as top-level names, so the entry is simply dropped; the
# parent directory that makes ``cloud_driver_installer`` importable is untouched.
_own_directory = _os.path.dirname(_os.path.abspath(__file__))
# "" is the current directory, which abspath() resolves, so it survives unless it *is* this one.
_sys.path[:] = [entry for entry in _sys.path if _os.path.abspath(entry) != _own_directory]
del _own_directory

__version__ = "1.0.7"
