"""Publish runner-owned outputs only after the entire capture has been validated."""

import os
import shutil
import tempfile
from pathlib import Path


class CaptureTransaction:
    def __init__(self):
        self.outputs = {}
        self.directories = []
        self.retain_recovery_files = False

    def stage(self, destination: Path) -> Path:
        destination = destination.absolute()
        if destination not in self.outputs:
            if destination.is_symlink():
                raise RuntimeError(f"Refusing to replace a symlink capture output: {destination}")
            destination.parent.mkdir(parents=True, exist_ok=True)
            directory = Path(tempfile.mkdtemp(prefix=".field-capture-", dir=destination.parent))
            self.directories.append(directory)
            self.outputs[destination] = directory / destination.name
        return self.outputs[destination]

    def publish(self):
        # Check every output before replacing any; a successful child may still have
        # omitted a file. Each replacement is atomic on this filesystem. A UI drive
        # with multiple outputs also rolls back earlier replacements on an I/O error.
        backups = {}
        for destination, staged in self.outputs.items():
            if staged.is_symlink() or not staged.is_file() or staged.stat().st_size == 0:
                raise RuntimeError(f"Capture produced no nonempty output: {destination}")
            if destination.is_symlink():
                raise RuntimeError(f"Capture output became a symlink: {destination}")
            if destination.exists():
                backup_dir = staged.parent / "backup"
                backup_dir.mkdir()
                backup = backup_dir / destination.name
                shutil.copy2(destination, backup)
                backups[destination] = backup
        published = []
        try:
            for destination, staged in self.outputs.items():
                os.replace(staged, destination)
                published.append(destination)
        except BaseException as error:
            rollback_errors = []
            for destination in reversed(published):
                try:
                    if destination in backups:
                        os.replace(backups[destination], destination)
                    else:
                        destination.unlink()
                except OSError as rollback_error:
                    rollback_errors.append(f"{destination}: {rollback_error}")
            if rollback_errors:
                self.retain_recovery_files = True
                raise RuntimeError("Capture publication and rollback failed. Recovery files retained at "
                                   f"{self.directories}: {'; '.join(rollback_errors)}") from error
            raise

    def close(self):
        if self.retain_recovery_files:
            return
        for directory in self.directories:
            shutil.rmtree(directory)
