@echo off
setlocal
cd /d "%~dp0"
title Reality3D server

rem Python: the "py" launcher if there is one, else "python".
set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY where python >nul 2>nul && set "PY=python"
if not defined PY goto nopython
%PY% -c "import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)" >nul 2>nul
if errorlevel 1 goto nopython

if not exist ".venv\Scripts\python.exe" (
  echo Setting up for the first time. This takes a minute...
  %PY% -m venv .venv
  if errorlevel 1 goto failed
)
".venv\Scripts\python.exe" -m pip install --quiet --disable-pip-version-check -r requirements.txt
if errorlevel 1 goto nolibs
rem The photo builder is optional: the server still starts without it.
".venv\Scripts\python.exe" -m pip install --quiet --disable-pip-version-check -r requirements-photos.txt
if errorlevel 1 echo Note: the photo builder (3D from many photos) couldn't be installed here; the rest works.

".venv\Scripts\python.exe" run.py
echo.
echo The server stopped.
pause
exit /b 0

:nopython
echo Python 3.10 or newer is needed. Get it from https://www.python.org/downloads/
echo and tick "Add python.exe to PATH" in the installer, then double-click this file again.
pause
exit /b 1

:nolibs
echo Couldn't install the libraries. Check the internet connection and try again.
pause
exit /b 1

:failed
echo Couldn't set up Python's environment here. Try a folder without special characters in its name.
pause
exit /b 1
