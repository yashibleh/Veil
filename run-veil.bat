@echo off
setlocal
cd /d "%~dp0"
echo Launching VEIL...
where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Java 17+ JDK was not found on PATH.
    echo Install Temurin 17/21 LTS: winget install EclipseAdoptium.Temurin.21.JDK
    exit /b 1
)
where mvn >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Apache Maven was not found on PATH.
    echo Install Maven: winget install Apache.Maven
    exit /b 1
)
call mvn -q -DskipTests package
if errorlevel 1 exit /b 1
java -jar target\veil-privacy-gateway-1.0.0.jar %*
endlocal
