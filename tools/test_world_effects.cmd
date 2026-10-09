@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat" >nul
cl /nologo /EHsc /std:c++20 "%~dp0test_world_effects.cpp" /Fo:"%TEMP%\skycraft-effects72.obj" /Fe:"%TEMP%\skycraft-effects72.exe" d3d11.lib d3dcompiler.lib > "%TEMP%\skycraft-effects72-build.txt" 2>&1
if errorlevel 1 (type "%TEMP%\skycraft-effects72-build.txt" & exit /b 1)
"%TEMP%\skycraft-effects72.exe" "%~dp0..\skse\src\Overlay.cpp" "%~dp0..\skse\src\WorldRender.cpp"
