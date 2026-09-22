"""Entry point: the connection dialog, then the main window."""

from __future__ import annotations

import sys


def main() -> int:
    """Start the installer GUI; returns a process exit code."""
    try:
        import tkinter as tk
    except ImportError:  # pragma: no cover - depends on the operator's Python build
        print("This tool needs tkinter. On Debian/Ubuntu: apt-get install python3-tk", file=sys.stderr)
        return 1

    from cloud_driver_installer.gui.app import MainWindow
    from cloud_driver_installer.gui.connect import ConnectDialog
    from cloud_driver_installer.gui.state import AppState
    from cloud_driver_installer.gui.widgets import init_styles

    try:
        root = tk.Tk()
    except tk.TclError as exc:  # pragma: no cover - headless machine
        print(f"No display available for the installer GUI: {exc}", file=sys.stderr)
        return 1
    init_styles(root)

    def connected(session, remote, target) -> None:
        """Swap the dialog for the main window once the session is open."""
        from cloud_driver_installer.gui.worker import LogEvent

        for child in root.winfo_children():
            if isinstance(child, tk.Widget):
                child.destroy()
        state = AppState()
        state.session, state.remote, state.plan.ssh = session, remote, target
        window = MainWindow(root, state)
        # Route the remote's own command/output lines into the window's log, redacted.
        remote.log = lambda level, message: window.worker.events.put(LogEvent(level, message))
        remote.redact = state.redactor.redact
        root.after(200, window.check_all)

    ConnectDialog(root, connected)
    root.mainloop()
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
