"""GUI installer for the cloud-driver system.

Everything that talks to the server goes through :mod:`cloud_driver_installer.remote`, everything
that talks to AWS through :mod:`cloud_driver_installer.aws`, and every unit of work is a
:class:`cloud_driver_installer.engine.Step` (check -> apply -> verify, idempotent). The tkinter
window under :mod:`cloud_driver_installer.gui` only drives those; it holds no logic of its own, so
the engine can be tested (and later run headless) without a display.
"""

__version__ = "1.0.7"
