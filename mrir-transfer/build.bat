@echo off
cd /d "%~dp0"
if not exist build mkdir build
javac --release 17 --add-modules jdk.httpserver -encoding UTF-8 -d build src\com\tuan\transfer\*.java
if errorlevel 1 exit /b 1
xcopy /E /I /Y web build\web >nul
jar --create --file mrir-transfer.jar --main-class com.tuan.transfer.App -C build .
if errorlevel 1 exit /b 1
echo Build finished. Run start.bat.
