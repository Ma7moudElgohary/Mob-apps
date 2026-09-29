#!/bin/sh
# Starts the Reality3D server on macOS or Linux. The first run sets everything up.
cd "$(dirname "$0")" || exit 1
PY=${PYTHON:-python3}
if ! "$PY" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
  echo "Python 3.10 or newer is needed: https://www.python.org/downloads/"
  exit 1
fi
if [ ! -x .venv/bin/python ]; then
  echo "Setting up for the first time. This takes a minute..."
  "$PY" -m venv .venv || { echo "Couldn't set up Python's environment here."; exit 1; }
fi
.venv/bin/python -m pip install --quiet --disable-pip-version-check -r requirements.txt \
  || { echo "Couldn't install the libraries. Check the internet connection and try again."; exit 1; }
# The photo builder is optional: the server still starts without it.
.venv/bin/python -m pip install --quiet --disable-pip-version-check -r requirements-photos.txt \
  || echo "Note: the photo builder (3D from many photos) couldn't be installed here; the rest works."
exec .venv/bin/python run.py
