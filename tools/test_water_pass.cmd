@echo off
call "C:\Program Files\Microsoft Visual Studio\2022\Professional\VC\Auxiliary\Build\vcvars64.bat" >nul
cl /nologo /EHsc /std:c++20 "%~dp0test_water_pass.cpp" /Fo:"%TEMP%\skycraft-water-test60.obj" /Fe:"%TEMP%\skycraft-water-test60.exe" d3d11.lib d3dcompiler.lib > "%TEMP%\skycraft-water-test60-build.txt" 2>&1
if errorlevel 1 (type "%TEMP%\skycraft-water-test60-build.txt" & exit /b 1)
"%TEMP%\skycraft-water-test60.exe" "%~dp0..\skse\src\WorldRender.cpp"
