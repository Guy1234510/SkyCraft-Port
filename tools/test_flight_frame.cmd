@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat" >nul
cl /nologo /EHsc /std:c++20 "%~dp0test_flight_frame.cpp" /Fo:"%TEMP%\skycraft-flight80.obj" /Fe:"%TEMP%\skycraft-flight80.exe"
if errorlevel 1 exit /b 1
"%TEMP%\skycraft-flight80.exe"
