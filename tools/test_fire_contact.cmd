@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat" >nul
cl /nologo /EHsc /std:c++20 "%~dp0test_fire_contact.cpp" /Fo:"%TEMP%\skycraft-fire77.obj" /Fe:"%TEMP%\skycraft-fire77.exe"
if errorlevel 1 exit /b 1
"%TEMP%\skycraft-fire77.exe"
